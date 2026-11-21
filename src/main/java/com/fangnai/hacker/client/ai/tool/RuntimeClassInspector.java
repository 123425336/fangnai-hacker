package com.fangnai.hacker.client.ai.tool;

import com.fangnai.hacker.client.define.RuntimeNameMapper;
import com.fangnai.hacker.client.instrument.InstrumentationAccess;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 读取“当前 JVM 里真实在运行的类”。
 * jar 文件只是磁盘上的静态内容：类可能被 agent、mixin、ASM 转换器改写，磁盘上的 jar 也可能和已加载的版本不一致，
 * 所以分析已加载的类时应以运行时为准。这里提供两种只读手段：
 * 1. 反射：列出反射能看到的字段/方法/构造器，没有任何副作用，不需要 agent；
 *    但反射视图可能被类刻意遮住（成员在 JVM 里存在、能正常调用，却不出现在 getDeclaredXxx 里），
 *    所以“反射里没有”不等于“运行时没有”，需要字节码或编译期直接调用来佐证；
 * 2. 抓取字节码：用一个只记录、不修改的转换器，通过 retransform 取到 JVM 当前持有的字节码，不受反射遮蔽影响。
 */
public final class RuntimeClassInspector {
    private static final RuntimeClassInspector INSTANCE = new RuntimeClassInspector();
    /** 本模组所在的加载器：生成的工具类也由它解析依赖，所以同名类优先取这个加载器里的。 */
    private static final ClassLoader OWN_LOADER = RuntimeClassInspector.class.getClassLoader();
    private static final int MAX_LINES_PER_SECTION = 400;
    private static final int MAX_DIFF_LINES = 80;
    private static final Pattern DOTTED_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+");
    private static final Pattern LINKAGE_ERROR = Pattern.compile(
            "((?:NoSuchMethod|NoSuchField|NoClassDefFound|ClassNotFound|IllegalAccess|IncompatibleClassChange|AbstractMethod)\\w*):\\s*([^\\r\\n]+)");

    private RuntimeClassInspector() {
    }

    public static RuntimeClassInspector get() {
        return INSTANCE;
    }

    /** 找到的运行时类；origin 说明它是原本就已加载，还是为了读取而按需加载的（都不会初始化）。 */
    public record LoadedClass(Class<?> type, String origin) {
    }

    /** 查找结果；没找到时 failure 里是按需加载失败的原因（可能为空）。 */
    public record Lookup(List<LoadedClass> found, String failure) {
    }

    /** 类的成员集合：字段为“名字:描述符”，方法为“名字+描述符”（构造器名字是 &lt;init&gt;）。 */
    public record MemberSet(Set<String> fields, Set<String> methods) {
    }

    // ── 查找类 ────────────────────────────────────────────────────────────

    /** 按完整类名查找运行时的类：先找已加载的，找不到再尝试按需加载（只加载，不初始化）。 */
    public Lookup find(String rawName) {
        String name = normalizeName(rawName);
        if (name.isBlank()) {
            return new Lookup(List.of(), "类名为空");
        }
        List<LoadedClass> found = new ArrayList<>();
        String failure = "";
        Instrumentation instrumentation = InstrumentationAccess.getInstrumentation();
        if (instrumentation != null) {
            for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                if (type.getName().equals(name)) {
                    found.add(new LoadedClass(type, "已加载"));
                }
            }
        }
        if (found.isEmpty()) {
            String origin = instrumentation != null
                    ? "此前未被游戏加载，已按需加载（未初始化）"
                    : "agent 不可用，通过 Class.forName 获取（未初始化）";
            for (ClassLoader loader : candidateLoaders()) {
                try {
                    Class<?> type = Class.forName(name, false, loader);
                    if (found.stream().noneMatch(entry -> entry.type() == type)) {
                        found.add(new LoadedClass(type, origin));
                    }
                } catch (ClassNotFoundException ignored) {
                    // 这个加载器里没有，继续试下一个
                } catch (LinkageError error) {
                    failure = error.getClass().getSimpleName() + ": " + error.getMessage();
                }
            }
        }
        found.sort(Comparator.comparingInt(entry -> entry.type().getClassLoader() == OWN_LOADER ? 0 : 1));
        return new Lookup(found, failure);
    }

    private static List<ClassLoader> candidateLoaders() {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        if (OWN_LOADER != null) {
            loaders.add(OWN_LOADER);
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            loaders.add(context);
        }
        loaders.add(ClassLoader.getSystemClassLoader());
        return new ArrayList<>(loaders);
    }

    private static String normalizeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (name.endsWith(".class")) {
            name = name.substring(0, name.length() - ".class".length());
        }
        return name.replace('/', '.').replace('\\', '.');
    }

    // ── 反射读取成员 ──────────────────────────────────────────────────────

    /**
     * 用反射描述一个运行时类：父类链、接口、构造器、字段、方法（带参数类型和 JVM 描述符）。
     * memberFilter 不为空时只列出名字包含它的成员，并同时列出父类里匹配的成员。
     */
    public String describeMembers(LoadedClass loaded, String memberFilter) {
        Class<?> type = loaded.type();
        String filter = memberFilter == null ? "" : memberFilter.trim().toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder();
        builder.append("类：").append(type.getName()).append('\n');
        builder.append("来源：运行时反射（").append(loaded.origin()).append("），反映反射能看到的运行时成员"
                + "（可能不完整：类可以把成员从反射里遮住，字节码或编译期直接调用的结果才是最终依据）\n");
        builder.append("加载器：").append(loaderName(type.getClassLoader())).append('\n');
        Path source = codeSourcePath(type);
        // 被游戏加载器处理过的类，代码来源常常只是根目录，只有指向具体文件时才有参考价值
        if (source != null && Files.isRegularFile(source)) {
            builder.append("代码位置：").append(source).append('\n');
        }
        if (RuntimeNameMapper.get().needsRemap()) {
            builder.append("名称说明：正式环境里游戏本体的成员名是 m_数字_ / f_数字_，后面的“≙”标出对应的开发期名称；"
                    + "写源码时用开发期名称，编译后会自动改写成运行期名称。成员过滤同时匹配两种名字。\n");
        }
        builder.append("种类：").append(kindOf(type)).append("，修饰符：").append(Modifier.toString(type.getModifiers())).append('\n');
        builder.append("父类链：").append(superChain(type)).append('\n');
        builder.append("接口：").append(interfaceNames(type)).append('\n');
        if (!filter.isEmpty()) {
            builder.append("成员过滤：名字包含“").append(filter).append("”（同时列出父类中匹配的成员）\n");
        }
        builder.append('\n');
        if (filter.isEmpty()) {
            appendSection(builder, "构造器", () -> constructorLines(type));
        }
        appendSection(builder, "字段", () -> fieldLines(type, filter));
        appendSection(builder, "方法", () -> methodLines(type, filter));
        if (!filter.isEmpty()) {
            for (Class<?> parent = type.getSuperclass(); parent != null && parent != Object.class; parent = parent.getSuperclass()) {
                if (isPlatformClass(parent)) {
                    break;
                }
                List<String> inherited = new ArrayList<>();
                try {
                    inherited.addAll(fieldLines(parent, filter));
                    inherited.addAll(methodLines(parent, filter));
                } catch (Throwable ignored) {
                    // 父类成员读取失败不影响其余输出
                }
                if (!inherited.isEmpty()) {
                    builder.append("继承自 ").append(parent.getName()).append("：\n");
                    for (String line : inherited) {
                        builder.append("  ").append(line).append('\n');
                    }
                }
            }
        }
        return builder.toString();
    }

    private interface LineSource {
        Collection<String> lines() throws Throwable;
    }

    private static void appendSection(StringBuilder builder, String title, LineSource source) {
        builder.append(title);
        try {
            List<String> lines = new ArrayList<>(source.lines());
            builder.append("（").append(lines.size()).append("）：\n");
            int shown = Math.min(lines.size(), MAX_LINES_PER_SECTION);
            for (int i = 0; i < shown; i++) {
                builder.append("  ").append(lines.get(i)).append('\n');
            }
            if (lines.size() > shown) {
                builder.append("  ... 另有 ").append(lines.size() - shown).append(" 项未显示，可用 memberPattern 按名字过滤。\n");
            }
        } catch (Throwable throwable) {
            builder.append("：读取失败 ").append(throwable.getClass().getSimpleName()).append(": ").append(throwable.getMessage())
                    .append("（通常是某个成员用到的类型在运行时无法解析）\n");
        }
    }

    private static List<String> constructorLines(Class<?> type) {
        TreeMap<String, String> lines = new TreeMap<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            String desc = MethodType.methodType(void.class, constructor.getParameterTypes()).toMethodDescriptorString();
            String text = "- " + join(Modifier.toString(constructor.getModifiers() & Modifier.constructorModifiers()), simpleName(type))
                    + "(" + typeNames(constructor.getParameterTypes()) + ")    <init>" + desc;
            lines.put("<init>" + desc, text);
        }
        return new ArrayList<>(lines.values());
    }

    private static List<String> fieldLines(Class<?> type, String filter) {
        TreeMap<String, String> lines = new TreeMap<>();
        for (Field field : type.getDeclaredFields()) {
            if (!matches(field.getName(), filter)) {
                continue;
            }
            String desc = field.getType().descriptorString();
            String text = "- " + join(Modifier.toString(field.getModifiers() & Modifier.fieldModifiers()), field.getType().getTypeName())
                    + " " + field.getName() + (field.isSynthetic() ? " [synthetic]" : "") + devSuffix(field.getName())
                    + "    " + field.getName() + ":" + desc;
            lines.put(field.getName() + ":" + desc, text);
        }
        return new ArrayList<>(lines.values());
    }

    private static List<String> methodLines(Class<?> type, String filter) {
        TreeMap<String, String> lines = new TreeMap<>();
        for (Method method : type.getDeclaredMethods()) {
            if (!matches(method.getName(), filter)) {
                continue;
            }
            String desc = MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString();
            String text = "- " + join(Modifier.toString(method.getModifiers() & Modifier.methodModifiers()), method.getReturnType().getTypeName())
                    + " " + method.getName() + "(" + typeNames(method.getParameterTypes()) + ")"
                    + (method.isSynthetic() ? " [synthetic]" : "") + (method.isBridge() ? " [bridge]" : "")
                    + devSuffix(method.getName())
                    + "    " + method.getName() + desc;
            lines.put(method.getName() + desc, text);
        }
        return new ArrayList<>(lines.values());
    }

    /** 名字过滤：运行期名字和对应的开发期名字任意一个包含关键字就算匹配。 */
    private static boolean matches(String name, String filter) {
        return filter.isEmpty() || name.toLowerCase(Locale.ROOT).contains(filter)
                || devName(name).toLowerCase(Locale.ROOT).contains(filter);
    }

    /** 混淆名（m_数字_ / f_数字_）对应的开发期名称；不是混淆名或没有映射时返回空串。 */
    private static String devName(String runtimeName) {
        if (runtimeName.length() < 4 || (runtimeName.charAt(0) != 'm' && runtimeName.charAt(0) != 'f')
                || runtimeName.charAt(1) != '_') {
            return "";
        }
        String dev = RuntimeNameMapper.get().devNameOf(runtimeName);
        return dev == null || dev.equals(runtimeName) ? "" : dev;
    }

    private static String devSuffix(String runtimeName) {
        String dev = devName(runtimeName);
        return dev.isEmpty() ? "" : "  ≙ " + dev;
    }

    /** 给成员键（“名字:描述符”或“名字+描述符”）附上开发期名称，方便阅读差异。 */
    private static String annotate(String memberKey) {
        int end = memberKey.length();
        int paren = memberKey.indexOf('(');
        int colon = memberKey.indexOf(':');
        if (paren >= 0) {
            end = paren;
        }
        if (colon >= 0 && colon < end) {
            end = colon;
        }
        String dev = devName(memberKey.substring(0, end));
        return dev.isEmpty() ? memberKey : memberKey + "  ≙ " + dev;
    }

    private static String join(String modifiers, String rest) {
        return modifiers.isEmpty() ? rest : modifiers + " " + rest;
    }

    private static String typeNames(Class<?>[] types) {
        return Arrays.stream(types).map(Class::getTypeName).collect(Collectors.joining(", "));
    }

    private static String simpleName(Class<?> type) {
        String name = type.getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private static String kindOf(Class<?> type) {
        if (type.isAnnotation()) {
            return "@interface";
        }
        if (type.isInterface()) {
            return "interface";
        }
        if (type.isEnum()) {
            return "enum";
        }
        if (type.isRecord()) {
            return "record";
        }
        return "class";
    }

    private static String superChain(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Class<?> parent = type.getSuperclass(); parent != null; parent = parent.getSuperclass()) {
            names.add(parent.getName());
        }
        return names.isEmpty() ? "（无）" : String.join(" -> ", names);
    }

    private static String interfaceNames(Class<?> type) {
        Class<?>[] interfaces = type.getInterfaces();
        return interfaces.length == 0 ? "（无）" : typeNames(interfaces);
    }

    private static boolean isPlatformClass(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.");
    }

    public static String loaderName(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        String name = loader.getName();
        return loader.getClass().getName() + (name == null || name.isBlank() ? "" : " (" + name + ")");
    }

    /** 类的代码来源对应的本地文件；拿不到（比如特殊的 URL 协议）时返回 null。 */
    public static Path codeSourcePath(Class<?> type) {
        try {
            ProtectionDomain domain = type.getProtectionDomain();
            if (domain == null || domain.getCodeSource() == null || domain.getCodeSource().getLocation() == null) {
                return null;
            }
            String text = domain.getCodeSource().getLocation().toString();
            if (text.startsWith("jar:")) {
                text = text.substring(4);
            }
            int bang = text.indexOf("!/");
            if (bang >= 0) {
                text = text.substring(0, bang);
            }
            return Path.of(URI.create(text)).toRealPath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ── 成员集合与差异 ────────────────────────────────────────────────────

    /** 用反射得到成员集合；读取失败（成员类型无法解析等）时返回 null。 */
    public static MemberSet memberSetFromReflection(Class<?> type) {
        try {
            Set<String> fields = new TreeSet<>();
            Set<String> methods = new TreeSet<>();
            for (Field field : type.getDeclaredFields()) {
                fields.add(field.getName() + ":" + field.getType().descriptorString());
            }
            for (Method method : type.getDeclaredMethods()) {
                methods.add(method.getName()
                        + MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString());
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                methods.add("<init>" + MethodType.methodType(void.class, constructor.getParameterTypes()).toMethodDescriptorString());
            }
            return new MemberSet(fields, methods);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 解析 class 字节码得到成员集合（不含静态初始化块）；字节码无法解析时返回 null。 */
    public static MemberSet memberSetFromBytes(byte[] bytes) {
        try {
            Set<String> fields = new TreeSet<>();
            Set<String> methods = new TreeSet<>();
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    fields.add(name + ":" + descriptor);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!"<clinit>".equals(name)) {
                        methods.add(name + descriptor);
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return new MemberSet(fields, methods);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 对比运行时与 jar 的成员，返回人能直接读的差异说明。 */
    public static String diffMembers(MemberSet runtime, MemberSet jar, boolean runtimeFromReflection) {
        if (runtime == null || jar == null) {
            return "成员对比失败：某一侧的成员无法解析。\n";
        }
        List<String> onlyRuntime = new ArrayList<>();
        List<String> onlyJar = new ArrayList<>();
        for (String field : runtime.fields()) {
            if (!jar.fields().contains(field)) {
                onlyRuntime.add("字段 " + annotate(field));
            }
        }
        for (String method : runtime.methods()) {
            if (!jar.methods().contains(method)) {
                onlyRuntime.add("方法 " + annotate(method));
            }
        }
        for (String field : jar.fields()) {
            if (!runtime.fields().contains(field)) {
                onlyJar.add("字段 " + annotate(field));
            }
        }
        for (String method : jar.methods()) {
            if (!runtime.methods().contains(method)) {
                onlyJar.add("方法 " + annotate(method));
            }
        }
        if (onlyRuntime.isEmpty() && onlyJar.isEmpty()) {
            return "成员（字段/方法/构造器）与 jar 完全一致。\n";
        }
        StringBuilder builder = new StringBuilder();
        if (runtimeFromReflection) {
            // 反射视图可能不完整：类可以把成员从反射里遮住，而 JVM 仍然能正常解析和调用它们
            appendDiff(builder, "仅运行时有（反射里有而 jar 里没有，通常是被改写或注入的成员）", onlyRuntime);
            appendDiff(builder, "仅 jar 有（反射里没有。这不等于运行时真的没有：成员可能只是被遮出了反射，也可能确实被移除了）", onlyJar);
            if (!onlyJar.isEmpty()) {
                builder.append("提醒：反射看不到成员，不能据此断定按 jar 里的名字调用会失败。验证方法：");
                builder.append("用 mode=runtime_javap 读运行时字节码（不受反射遮蔽影响），或者写编译期直接调用（不要用反射）实际试一次并返回 e.toString()。");
                builder.append("NoSuchMethodError/NoSuchFieldError 这类 JVM 链接错误才说明运行时真的没有，NoSuchMethodException/NoSuchFieldException 这类反射异常只说明反射看不到。\n");
                if (countNonConstructorMembers(runtime) == 0 && countNonConstructorMembers(jar) > 0) {
                    builder.append("特别注意：这个类在反射里没有任何字段和普通方法，而 jar 里有 ")
                            .append(countNonConstructorMembers(jar)).append(" 个，这是反射被整体遮住的典型特征，不是类被清空了。\n");
                }
            }
        } else {
            appendDiff(builder, "仅运行时有（jar 里没有，通常是被改写或注入的成员）", onlyRuntime);
            appendDiff(builder, "仅 jar 有（运行时字节码里已经没有，按 jar 里的名字调用会失败）", onlyJar);
        }
        return builder.toString();
    }

    /** 成员集合里字段和普通方法的总数（不含构造器），用来识别“反射里空空如也”的情况。 */
    private static int countNonConstructorMembers(MemberSet set) {
        int count = set.fields().size();
        for (String method : set.methods()) {
            if (!method.startsWith("<init>")) {
                count++;
            }
        }
        return count;
    }

    /** 两边的字段/方法集合是否完全相同。 */
    public static boolean sameMembers(MemberSet runtime, MemberSet jar) {
        return runtime != null && jar != null
                && runtime.fields().equals(jar.fields()) && runtime.methods().equals(jar.methods());
    }

    // ── 方法体对比 ────────────────────────────────────────────────────────

    /**
     * 给每个方法的“修饰符 + 指令序列”算一个摘要（忽略行号、局部变量名、栈帧等调试信息和方法排列顺序）。
     * 用来判断两份字节码的方法体是否真的不同：加载器重新序列化类时常常只会改变排序和调试信息，
     * 逐字节比较会误报成“被改写”。字节码无法解析时返回 null。
     */
    public static java.util.Map<String, String> methodBodyHashes(byte[] bytes) {
        try {
            java.util.Map<String, String> result = new TreeMap<>();
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new BodyHasher(name + descriptor, access, result);
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 两边都有、但方法体摘要不同的方法（带开发期名称标注）。任意一边为 null 时返回空列表。 */
    public static List<String> changedMethodBodies(java.util.Map<String, String> runtime, java.util.Map<String, String> jar) {
        List<String> changed = new ArrayList<>();
        if (runtime == null || jar == null) {
            return changed;
        }
        for (java.util.Map.Entry<String, String> entry : runtime.entrySet()) {
            String other = jar.get(entry.getKey());
            if (other != null && !other.equals(entry.getValue())) {
                changed.add(annotate(entry.getKey()));
            }
        }
        return changed;
    }

    /** 列出方法体差异。 */
    public static String describeChangedBodies(List<String> changed) {
        StringBuilder builder = new StringBuilder();
        appendDiff(builder, "方法体与 jar 不同（指令序列有变化，说明这些方法被改写过）", changed);
        return builder.toString();
    }

    /** 把一个方法的指令序列规范化成文本再取摘要；标签按首次出现的顺序编号，避免对象地址影响结果。 */
    private static final class BodyHasher extends MethodVisitor {
        private final String key;
        private final java.util.Map<String, String> sink;
        private final StringBuilder tokens = new StringBuilder();
        private final java.util.Map<Label, Integer> labels = new java.util.IdentityHashMap<>();

        BodyHasher(String key, int access, java.util.Map<String, String> sink) {
            super(Opcodes.ASM9);
            this.key = key;
            this.sink = sink;
            tokens.append("acc=").append(access).append(';');
        }

        private int id(Label label) {
            return labels.computeIfAbsent(label, ignored -> labels.size());
        }

        @Override
        public void visitInsn(int opcode) {
            tokens.append(opcode).append(';');
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            tokens.append(opcode).append(' ').append(operand).append(';');
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            tokens.append(opcode).append(' ').append(varIndex).append(';');
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            tokens.append(opcode).append(' ').append(type).append(';');
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            tokens.append(opcode).append(' ').append(owner).append('.').append(name).append(':').append(descriptor).append(';');
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            tokens.append(opcode).append(' ').append(owner).append('.').append(name).append(descriptor)
                    .append(isInterface ? "I" : "").append(';');
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            tokens.append("indy ").append(name).append(descriptor).append(' ').append(bootstrapMethodHandle)
                    .append(Arrays.toString(bootstrapMethodArguments)).append(';');
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            tokens.append(opcode).append(" L").append(id(label)).append(';');
        }

        @Override
        public void visitLabel(Label label) {
            tokens.append('L').append(id(label)).append(':');
        }

        @Override
        public void visitLdcInsn(Object value) {
            tokens.append("ldc ").append(value).append(';');
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            tokens.append("iinc ").append(varIndex).append(' ').append(increment).append(';');
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labelsInSwitch) {
            tokens.append("tableswitch ").append(min).append(' ').append(max).append(" L").append(id(dflt));
            for (Label label : labelsInSwitch) {
                tokens.append(" L").append(id(label));
            }
            tokens.append(';');
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labelsInSwitch) {
            tokens.append("lookupswitch L").append(id(dflt)).append(Arrays.toString(keys));
            for (Label label : labelsInSwitch) {
                tokens.append(" L").append(id(label));
            }
            tokens.append(';');
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            tokens.append("multianewarray ").append(descriptor).append(' ').append(numDimensions).append(';');
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            tokens.append("try L").append(id(start)).append(" L").append(id(end)).append(" L").append(id(handler))
                    .append(' ').append(type).append(';');
        }

        @Override
        public void visitEnd() {
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-1")
                        .digest(tokens.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < 8; i++) {
                    hex.append(String.format("%02x", digest[i]));
                }
                sink.put(key, hex.toString());
            } catch (java.security.NoSuchAlgorithmException exception) {
                sink.put(key, Integer.toHexString(tokens.toString().hashCode()));
            }
        }
    }

    private static void appendDiff(StringBuilder builder, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        builder.append(title).append("（").append(lines.size()).append("）：\n");
        int shown = Math.min(lines.size(), MAX_DIFF_LINES);
        for (int i = 0; i < shown; i++) {
            builder.append("  - ").append(lines.get(i)).append('\n');
        }
        if (lines.size() > shown) {
            builder.append("  ... 另有 ").append(lines.size() - shown).append(" 项未显示\n");
        }
    }

    // ── 抓取运行时字节码 ──────────────────────────────────────────────────

    /**
     * 抓取 JVM 当前持有的类字节码。
     * 做法：临时注册一个“只记录、不修改”的转换器（排在所有已注册转换器之后），再对目标类做一次 retransform，
     * 转换器收到的就是前面所有转换器处理之后的字节码；抓完立刻移除，自己不改动任何内容。
     * 注意 retransform 会把该类上已注册的其他转换器重新跑一遍。
     */
    public synchronized byte[] captureRuntimeBytes(Class<?> type) throws Exception {
        Instrumentation instrumentation = InstrumentationAccess.getInstrumentation();
        if (instrumentation == null) {
            throw new IllegalStateException("agent 未就绪（" + InstrumentationAccess.statusSummary() + "）");
        }
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("当前 JVM 不支持 retransform");
        }
        if (!instrumentation.isModifiableClass(type)) {
            throw new IllegalStateException("该类不允许 retransform（平台内部类、数组或基本类型）：" + type.getName());
        }
        AtomicReference<byte[]> captured = new AtomicReference<>();
        ClassFileTransformer collector = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
                if (classBeingRedefined == type && classfileBuffer != null) {
                    captured.set(classfileBuffer.clone());
                }
                // 返回 null 表示不修改字节码，只是旁路记录
                return null;
            }
        };
        instrumentation.addTransformer(collector, true);
        try {
            instrumentation.retransformClasses(type);
        } finally {
            instrumentation.removeTransformer(collector);
        }
        byte[] bytes = captured.get();
        if (bytes == null) {
            throw new IllegalStateException("retransform 完成但没有收到字节码");
        }
        return bytes;
    }

    /** 把抓到的字节码保存到缓存目录，供 javap 读取。 */
    public Path saveRuntimeBytes(Class<?> type, byte[] bytes) throws IOException {
        Path dir = ToolRegistry.get().root().resolve("cache").resolve("runtime-classes");
        Files.createDirectories(dir);
        Path file = dir.resolve(type.getName().replaceAll("[^A-Za-z0-9._$-]", "_") + ".class");
        Files.write(file, bytes);
        return file;
    }

    // ── 失败时自动附带运行时结构 ──────────────────────────────────────────

    /**
     * 在报错/返回值/聊天输出里查找“找不到方法、字段、类”这类链接错误，
     * 把错误里提到的类在运行时的结构整理出来，让 AI 不用凭 jar 里的名字猜。没有相关错误时返回空串。
     * 结构来自反射，可能被遮住，所以会按异常类型补充说明：
     * ……Error 是 JVM 链接层报的，说明字节码里确实没有；……Exception 是反射层报的，只说明反射看不到。
     */
    public String describeForLinkageFailures(String... texts) {
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();
        boolean sawLinkageError = false;
        boolean sawReflectionException = false;
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            Matcher matcher = LINKAGE_ERROR.matcher(text);
            int guard = 0;
            while (matcher.find() && guard++ < 4 && seen.size() < 2) {
                String errorType = matcher.group(1);
                // 只有“找不到”类的错误才区分是链接层还是反射层；访问权限、抽象方法之类不套用这套说明
                boolean missing = errorType.startsWith("NoSuch") || errorType.startsWith("NoClassDefFound")
                        || errorType.startsWith("ClassNotFound");
                if (missing && errorType.endsWith("Exception")) {
                    sawReflectionException = true;
                } else if (missing) {
                    sawLinkageError = true;
                }
                String message = matcher.group(2).replace('/', '.');
                int paren = message.indexOf('(');
                if (paren >= 0) {
                    message = message.substring(0, paren);
                }
                Matcher names = DOTTED_NAME.matcher(message);
                while (names.find() && seen.size() < 2) {
                    String candidate = names.group();
                    String memberName = "";
                    // 报错里常是“类名.成员名”，从后往前去掉一段，直到能找到类
                    for (int i = 0; i < 6 && candidate.contains("."); i++) {
                        Lookup lookup = find(candidate);
                        if (!lookup.found().isEmpty()) {
                            LoadedClass loaded = lookup.found().get(0);
                            if (seen.add(loaded.type().getName())) {
                                out.append("【系统自动附带】报错里提到的类 ").append(loaded.type().getName())
                                        .append(" 在运行时的结构（来自反射，可能不完整，见文末说明）：\n")
                                        .append(describeMembers(loaded, memberName)).append('\n');
                            }
                            break;
                        }
                        int dot = candidate.lastIndexOf('.');
                        if (memberName.isBlank()) {
                            memberName = candidate.substring(dot + 1);
                        }
                        candidate = candidate.substring(0, dot);
                    }
                }
            }
        }
        if (out.length() > 0) {
            if (sawLinkageError) {
                out.append("【系统说明】报错是 JVM 链接错误（……Error）：说明运行时字节码里确实没有这个成员或类，"
                        + "按上面列出的成员改（列表来自反射，可能漏掉被遮住的成员，拿不准时用 mode=runtime_javap 看字节码）。\n");
            }
            if (sawReflectionException) {
                out.append("【系统说明】报错是反射异常（……Exception）：只说明反射找不到，不说明运行时真的没有——类可以把成员从反射里遮住，"
                        + "而编译期直接调用照样能成功。不要因此放弃或改走别的方案：先改成编译期直接调用（不用反射）再试一次，"
                        + "失败时把 e.toString() 写进返回值；上面的列表里没有它，也要这样验证，不要只凭列表断定。\n");
            }
        }
        return out.toString();
    }
}
