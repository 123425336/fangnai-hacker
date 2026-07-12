package com.fangnai.hacker.client.transform;

import com.fangnai.hacker.client.ai.tool.ToolRegistry;
import com.fangnai.hacker.client.command.ChatFeedback;
import com.fangnai.hacker.client.instrument.InstrumentationAccess;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import net.minecraftforge.fml.loading.FMLPaths;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.IllegalClassFormatException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

public final class TransformService {
    private static final TransformService INSTANCE = new TransformService();
    private static final int MAX_TARGETS = 500;
    private static final long HOTSPOT_17_CLASS_KLASS_OFFSET = 16L;
    private static final long HOTSPOT_17_KLASS_FLAGS_OFFSET = 164L;
    private static final int HOTSPOT_HIDDEN_CLASS_FLAG = 0x04000000;
    private static final sun.misc.Unsafe UNSAFE = findUnsafe();

    private TransformService() {
    }

    public static TransformService get() {
        return INSTANCE;
    }

    public void applyVoidReturnTransform(String targetPattern, String methodPattern, String reason, String expectedEffect) {
        applyDefaultReturnTransform(targetPattern, methodPattern, "void_return", reason, expectedEffect);
    }

    public void applyDefaultReturnTransform(String targetPattern, String methodPattern, String returnMode,
                                            String reason, String expectedEffect) {
        Instrumentation instrumentation = InstrumentationAccess.getInstrumentation();
        if (instrumentation == null) {
            ChatFeedback.error("动态 transform 需要 agent attach 成功。");
            return;
        }
        ReturnMode mode = ReturnMode.byName(returnMode);
        if (mode == ReturnMode.REFERENCE_NULL && (methodPattern == null || methodPattern.isBlank())) {
            ChatFeedback.error("reference_null 风险过高，必须提供精确 methodPattern，禁止对整类引用返回方法统一置 null。");
            return;
        }
        List<Class<?>> targets = resolveTargets(instrumentation, targetPattern);
        if (targets.isEmpty()) {
            redefineFromMods(instrumentation, targetPattern, methodPattern, mode, reason, expectedEffect);
            return;
        }
        if (!instrumentation.isRetransformClassesSupported()) {
            ChatFeedback.error("JVM 不支持 retransform 已加载类。");
            return;
        }
        if (targets.size() > MAX_TARGETS) {
            ChatFeedback.warn("匹配类过多（" + targets.size() + "），请缩小目标。最多允许 " + MAX_TARGETS + " 个。");
            return;
        }
        OwnedTransformAttempt ownedAttempt = tryTargetOwnedInstrumentation(
                instrumentation, targetPattern, targets, methodPattern, mode);
        if (ownedAttempt != null && ownedAttempt.attempt().success()
                && ownedAttempt.attempt().transformedMethods() > 0) {
            TransformAttempt result = ownedAttempt.attempt();
            List<String> resolvedNames = ownedAttempt.targets().stream().map(Class::getName).toList();
            JsonObject recipe = createRecipe(targetPattern, methodPattern, mode, reason, expectedEffect,
                    result.transformedMethods(), resolvedNames);
            recipe.addProperty("transformInvocations", result.transformInvocations());
            recipe.addProperty("instrumentationSource", "target_owned");
            recipe.addProperty("hiddenClasses", ownedAttempt.hiddenClasses());
            ToolRegistry.get().recordRecipe("default-return " + targetPattern, recipe.toString(), riskLevel(targets));
            ChatFeedback.info("已使用目标来源持有的 Instrumentation 完成 transform：类=" + ownedAttempt.targets().size()
                    + "，hidden=" + ownedAttempt.hiddenClasses() + "，修改方法数=" + result.transformedMethods()
                    + "，transform 回调=" + result.transformInvocations() + "。已排除 Lambda/代理等 JVM 生成隐藏类。");
            return;
        }
        TransformAttempt attempt = retransform(instrumentation, targets, methodPattern, mode);
        if (!attempt.success()) {
            ChatFeedback.error("retransform 失败：" + attempt.error());
            return;
        }
        if (attempt.transformInvocations() == 0 && restoreInstrumentationTransformChain(instrumentation)) {
            ChatFeedback.warn("检测到 Instrumentation 转换链未调用本 transformer；已从当前 JDK runtime image 恢复标准转换入口，正在重试。");
            attempt = retransform(instrumentation, targets, methodPattern, mode);
            if (!attempt.success()) {
                ChatFeedback.error("恢复 Instrumentation 转换链后重试失败：" + attempt.error());
                return;
            }
        }
        if (attempt.transformedMethods() == 0) {
            int skipped = countUnmodifiableMatches(instrumentation, targetPattern);
            ChatFeedback.warn("匹配到 " + targets.size() + " 个可修改类，但实际修改 0 个方法，transform 回调="
                    + attempt.transformInvocations() + "；不可修改/隐藏匹配类=" + skipped
                    + "。改用 mods 原始字节码插桩并 redefine。");
            boolean fallbackApplied = redefineFromMods(instrumentation, targetPattern, methodPattern, mode, reason, expectedEffect);
            if (fallbackApplied && restoreInstrumentationTransformChain(instrumentation)) {
                TransformAttempt finalAttempt = retransform(instrumentation, targets, methodPattern, mode);
                if (finalAttempt.success() && finalAttempt.transformedMethods() > 0) {
                    ChatFeedback.info("mods direct redefine 已停用目标控制链；恢复标准 Instrumentation 后重试成功：修改方法数="
                            + finalAttempt.transformedMethods() + "，transform 回调=" + finalAttempt.transformInvocations() + "。");
                } else {
                    ChatFeedback.warn("mods direct redefine 后的最终 retransform 仍未进入转换器：callbacks="
                            + finalAttempt.transformInvocations() + "，methods=" + finalAttempt.transformedMethods()
                            + (finalAttempt.success() ? "" : "，error=" + finalAttempt.error()));
                }
            }
            return;
        }

        List<String> resolvedNames = targets.stream().map(Class::getName).toList();
        JsonObject recipe = createRecipe(targetPattern, methodPattern, mode, reason, expectedEffect,
                attempt.transformedMethods(), resolvedNames);
        recipe.addProperty("transformInvocations", attempt.transformInvocations());
        recipe.addProperty("unmodifiableMatches", countUnmodifiableMatches(instrumentation, targetPattern));
        ToolRegistry.get().recordRecipe("default-return " + targetPattern, recipe.toString(), riskLevel(targets));
        ChatFeedback.info("已 retransform " + targets.size() + " 个类。模式=" + mode.configName
                + "，修改方法数=" + attempt.transformedMethods() + "，transform 回调=" + attempt.transformInvocations()
                + "。已按返回类型返回 JVM 默认值；重启游戏可恢复原始字节码。");
    }

    private OwnedTransformAttempt tryTargetOwnedInstrumentation(Instrumentation defaultInstrumentation,
                                                                 String targetPattern, List<Class<?>> normalTargets,
                                                                 String methodPattern, ReturnMode mode) {
        List<InstrumentationOwner> owners = findTargetOwnedInstrumentations(
                defaultInstrumentation, targetPattern);
        if (owners.isEmpty()) {
            return null;
        }
        Map<String, byte[]> templates;
        try {
            templates = findClassBytesInMods(targetPattern);
        } catch (Throwable throwable) {
            templates = Map.of();
        }
        for (InstrumentationOwner owner : owners) {
            List<HiddenClassState> hiddenStates = unlockTemplateBackedHiddenClasses(
                    owner.instrumentation(), targetPattern, templates.keySet());
            try {
                List<Class<?>> combinedTargets = orderTargets(normalTargets, hiddenStates, owner.holderClasses());
                if (combinedTargets.isEmpty() || combinedTargets.size() > MAX_TARGETS) {
                    continue;
                }
                TransformAttempt attempt = retransform(owner.instrumentation(), combinedTargets, methodPattern, mode);
                if (attempt.transformInvocations() > 0) {
                    return new OwnedTransformAttempt(owner.instrumentation(), combinedTargets,
                            hiddenStates.size(), attempt);
                }
            } finally {
                restoreHiddenFlags(hiddenStates);
            }
        }
        return null;
    }

    private List<InstrumentationOwner> findTargetOwnedInstrumentations(Instrumentation defaultInstrumentation,
                                                                        String targetPattern) {
        Map<Instrumentation, List<Class<?>>> owners = new IdentityHashMap<>();
        for (Class<?> type : defaultInstrumentation.getAllLoadedClasses()) {
            if (type == null || !classMatches(type, targetPattern)) {
                continue;
            }
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || !Instrumentation.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    if (!field.trySetAccessible()) {
                        continue;
                    }
                    Object value = field.get(null);
                    if (value instanceof Instrumentation candidate && candidate != defaultInstrumentation) {
                        owners.computeIfAbsent(candidate, ignored -> new ArrayList<>()).add(type);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        List<InstrumentationOwner> result = new ArrayList<>();
        owners.forEach((instrumentation, holders) -> result.add(
                new InstrumentationOwner(instrumentation, List.copyOf(holders))));
        return result;
    }

    private List<Class<?>> orderTargets(List<Class<?>> normalTargets, List<HiddenClassState> hiddenStates,
                                        List<Class<?>> ownershipHolders) {
        Set<Class<?>> holders = Collections.newSetFromMap(new IdentityHashMap<>());
        holders.addAll(ownershipHolders);
        Set<Class<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Class<?>> ordered = new ArrayList<>();
        for (HiddenClassState hidden : hiddenStates) {
            if (seen.add(hidden.type())) {
                ordered.add(hidden.type());
            }
        }
        for (Class<?> target : normalTargets) {
            if (!holders.contains(target) && seen.add(target)) {
                ordered.add(target);
            }
        }
        for (Class<?> holder : ownershipHolders) {
            if (seen.add(holder)) {
                ordered.add(holder);
            }
        }
        return ordered;
    }

    private List<HiddenClassState> unlockTemplateBackedHiddenClasses(Instrumentation instrumentation,
                                                                      String targetPattern,
                                                                      Set<String> templateClassNames) {
        List<HiddenClassState> states = new ArrayList<>();
        if (!supportsHotSpot17HiddenFlagAccess() || templateClassNames.isEmpty()) {
            return states;
        }
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (type == null || !type.isHidden() || !classMatches(type, targetPattern)) {
                continue;
            }
            String runtimeName = type.getName();
            String stableName = runtimeName.contains("/")
                    ? runtimeName.substring(0, runtimeName.indexOf('/')) : runtimeName;
            String lower = stableName.toLowerCase(Locale.ROOT);
            if (lower.contains("$$lambda$") || lower.contains("$$proxy")
                    || lower.contains("stringconcat") || !templateClassNames.contains(stableName)) {
                continue;
            }
            try {
                long klass = UNSAFE.getLong(type, HOTSPOT_17_CLASS_KLASS_OFFSET);
                if (klass == 0L || (klass & 0x7L) != 0L) {
                    continue;
                }
                long flagsAddress = klass + HOTSPOT_17_KLASS_FLAGS_OFFSET;
                int flags = UNSAFE.getInt(flagsAddress);
                if ((flags & HOTSPOT_HIDDEN_CLASS_FLAG) == 0) {
                    continue;
                }
                UNSAFE.putInt(flagsAddress, flags & ~HOTSPOT_HIDDEN_CLASS_FLAG);
                if (instrumentation.isModifiableClass(type)) {
                    states.add(new HiddenClassState(type, flagsAddress, flags));
                } else {
                    UNSAFE.putInt(flagsAddress, flags);
                }
            } catch (Throwable ignored) {
            }
        }
        return states;
    }

    private void restoreHiddenFlags(List<HiddenClassState> states) {
        for (HiddenClassState state : states) {
            try {
                UNSAFE.putInt(state.flagsAddress(), state.originalFlags());
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean supportsHotSpot17HiddenFlagAccess() {
        if (UNSAFE == null || UNSAFE.addressSize() != 8 || Runtime.version().feature() != 17) {
            return false;
        }
        String vm = System.getProperty("java.vm.name", "").toLowerCase(Locale.ROOT);
        return vm.contains("hotspot") || vm.contains("openjdk");
    }

    private static sun.misc.Unsafe findUnsafe() {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (sun.misc.Unsafe) field.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private record InstrumentationOwner(Instrumentation instrumentation, List<Class<?>> holderClasses) {
    }

    private record OwnedTransformAttempt(Instrumentation instrumentation, List<Class<?>> targets,
                                         int hiddenClasses, TransformAttempt attempt) {
    }

    private record HiddenClassState(Class<?> type, long flagsAddress, int originalFlags) {
    }

    private TransformAttempt retransform(Instrumentation instrumentation, List<Class<?>> targets,
                                         String methodPattern, ReturnMode mode) {
        DefaultReturnTransformer transformer = new DefaultReturnTransformer(targets, methodPattern, mode);
        try {
            instrumentation.addTransformer(transformer, true);
            instrumentation.retransformClasses(targets.toArray(Class[]::new));
            return new TransformAttempt(true, transformer.transformInvocations(), transformer.transformedMethods(), "");
        } catch (Throwable throwable) {
            return new TransformAttempt(false, transformer.transformInvocations(), transformer.transformedMethods(),
                    throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage()));
        } finally {
            try {
                instrumentation.removeTransformer(transformer);
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean restoreInstrumentationTransformChain(Instrumentation instrumentation) {
        if (!instrumentation.isRedefineClassesSupported()) {
            return false;
        }
        try {
            Class<?> implementation = Class.forName("sun.instrument.InstrumentationImpl", false, null);
            if (!instrumentation.isModifiableClass(implementation)) {
                ChatFeedback.warn("Instrumentation 转换链疑似被拦截，但 InstrumentationImpl 不可 redefine。");
                return false;
            }
            byte[] runtimeBytes;
            try (InputStream input = implementation.getResourceAsStream("InstrumentationImpl.class")) {
                if (input == null) {
                    ChatFeedback.warn("无法从当前 JDK runtime image 读取 InstrumentationImpl 原始字节码。");
                    return false;
                }
                runtimeBytes = input.readAllBytes();
            }
            instrumentation.redefineClasses(new ClassDefinition(implementation, runtimeBytes));
            return true;
        } catch (Throwable throwable) {
            ChatFeedback.warn("恢复 Instrumentation 标准转换入口失败：" + throwable.getClass().getSimpleName()
                    + ": " + String.valueOf(throwable.getMessage()));
            return false;
        }
    }

    private record TransformAttempt(boolean success, int transformInvocations, int transformedMethods, String error) {
    }

    private boolean redefineFromMods(Instrumentation instrumentation, String targetPattern, String methodPattern,
                                  ReturnMode mode, String reason, String expectedEffect) {
        if (!instrumentation.isRedefineClassesSupported()) {
            ChatFeedback.warn("没有找到匹配的已加载类，且 JVM 不支持从 mods 原始字节码 redefine：" + targetPattern);
            return false;
        }
        try {
            Map<String, byte[]> matches = findClassBytesInMods(targetPattern);
            if (matches.isEmpty()) {
                ChatFeedback.warn("instrumentation 和 mods 目录都没有找到匹配类：" + targetPattern);
                return false;
            }
            if (matches.size() > MAX_TARGETS) {
                ChatFeedback.warn("mods 中匹配类过多（" + matches.size() + "），请缩小目标。最多允许 " + MAX_TARGETS + " 个。");
                return false;
            }

            List<ClassDefinition> definitions = new ArrayList<>();
            List<String> resolvedNames = new ArrayList<>();
            int transformedMethods = 0;
            for (Map.Entry<String, byte[]> match : matches.entrySet()) {
                Class<?> target = resolveWithoutInitialization(match.getKey());
                if (target == null || !instrumentation.isModifiableClass(target)) {
                    continue;
                }
                TransformResult transformed = transformBytes(match.getValue(), methodPattern, mode);
                if (transformed.transformedMethods() == 0) {
                    continue;
                }
                definitions.add(new ClassDefinition(target, transformed.bytes()));
                resolvedNames.add(match.getKey());
                transformedMethods += transformed.transformedMethods();
            }
            if (definitions.isEmpty()) {
                ChatFeedback.warn("mods 中找到了 " + matches.size() + " 个匹配 class，但当前模组类加载器无法解析/修改，或没有匹配的方法：" + targetPattern);
                return false;
            }

            instrumentation.redefineClasses(definitions.toArray(ClassDefinition[]::new));
            JsonObject recipe = createRecipe(targetPattern, methodPattern, mode, reason, expectedEffect,
                    transformedMethods, resolvedNames);
            recipe.addProperty("bytecodeSource", "mods");
            ToolRegistry.get().recordRecipe("default-return " + targetPattern, recipe.toString(), "medium");
            ChatFeedback.info("instrumentation 未发现匹配的已加载类；已从 mods 原始字节码插桩并 redefine "
                    + definitions.size() + " 个类。模式=" + mode.configName + "，修改方法数=" + transformedMethods + "。");
            return true;
        } catch (Throwable throwable) {
            ChatFeedback.error("从 mods 原始字节码插桩并 redefine 失败：" + throwable.getMessage());
            return false;
        }
    }

    private Map<String, byte[]> findClassBytesInMods(String targetPattern) throws Exception {
        Path mods = FMLPaths.GAMEDIR.get().resolve("mods").normalize();
        Map<String, byte[]> matches = new LinkedHashMap<>();
        if (!Files.isDirectory(mods)) {
            return matches;
        }
        try (Stream<Path> files = Files.list(mods)) {
            for (Path jar : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted().toList()) {
                boolean jarMatches = !matchToken(targetPattern).isBlank()
                        && matchToken(jar.getFileName().toString()).contains(matchToken(targetPattern));
                try (JarFile jarFile = new JarFile(jar.toFile())) {
                    var entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (entry.isDirectory() || !entry.getName().endsWith(".class")
                                || entry.getName().startsWith("META-INF/versions/")) {
                            continue;
                        }
                        String className = entry.getName().substring(0, entry.getName().length() - 6).replace('/', '.');
                        if ((!jarMatches && !classNameMatches(className, targetPattern)) || matches.containsKey(className)) {
                            continue;
                        }
                        try (InputStream input = jarFile.getInputStream(entry)) {
                            matches.put(className, input.readAllBytes());
                        }
                    }
                }
            }
        }
        return matches;
    }

    private Class<?> resolveWithoutInitialization(String className) {
        ClassLoader[] loaders = {
                Thread.currentThread().getContextClassLoader(),
                TransformService.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(className, false, loader);
            } catch (ClassNotFoundException | LinkageError ignored) {
            }
        }
        return null;
    }

    private boolean classNameMatches(String name, String targetPattern) {
        String pattern = targetPattern == null ? "" : targetPattern.trim();
        String normalized = pattern.toLowerCase(Locale.ROOT).replace('/', '.');
        String lower = name.toLowerCase(Locale.ROOT);
        if (!normalized.isBlank() && (lower.contains(normalized) || lower.startsWith(normalized))) {
            return true;
        }
        Pattern regex = tryRegex(pattern);
        return regex != null && regex.matcher(name).find();
    }

    private JsonObject createRecipe(String targetPattern, String methodPattern, ReturnMode mode,
                                    String reason, String expectedEffect, int transformedMethods,
                                    List<String> resolvedNames) {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("operation", "default_return_transform");
        recipe.addProperty("targetPattern", targetPattern);
        recipe.addProperty("methodPattern", methodPattern == null ? "" : methodPattern);
        recipe.addProperty("returnMode", mode.configName);
        recipe.addProperty("reason", reason);
        recipe.addProperty("expectedEffect", expectedEffect);
        recipe.addProperty("transformedMethods", transformedMethods);
        JsonArray classes = new JsonArray();
        resolvedNames.forEach(classes::add);
        recipe.add("resolvedClasses", classes);
        return recipe;
    }

    private TransformResult transformBytes(byte[] originalBytes, String methodPattern, ReturnMode mode)
            throws IllegalClassFormatException {
        DefaultReturnTransformer transformer = new DefaultReturnTransformer(List.of(), methodPattern, mode);
        byte[] transformed = transformer.transformBytes(originalBytes);
        return new TransformResult(transformed, transformer.transformedMethods());
    }

    private record TransformResult(byte[] bytes, int transformedMethods) {
    }

    private List<Class<?>> resolveTargets(Instrumentation instrumentation, String targetPattern) {
        List<Class<?>> targets = new ArrayList<>();
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            if (loadedClass == null || loadedClass.isArray() || loadedClass.isPrimitive()) {
                continue;
            }
            if (!instrumentation.isModifiableClass(loadedClass)) {
                continue;
            }
            if (classMatches(loadedClass, targetPattern)) {
                targets.add(loadedClass);
            }
        }
        return targets;
    }

    private int countUnmodifiableMatches(Instrumentation instrumentation, String targetPattern) {
        int count = 0;
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            if (loadedClass != null && !loadedClass.isArray() && !loadedClass.isPrimitive()
                    && classMatches(loadedClass, targetPattern) && !instrumentation.isModifiableClass(loadedClass)) {
                count++;
            }
        }
        return count;
    }

    private boolean classMatches(Class<?> loadedClass, String targetPattern) {
        if (classNameMatches(loadedClass.getName(), targetPattern)) {
            return true;
        }
        String patternToken = matchToken(targetPattern);
        if (patternToken.isBlank()) {
            return false;
        }
        try {
            ProtectionDomain domain = loadedClass.getProtectionDomain();
            if (domain == null || domain.getCodeSource() == null || domain.getCodeSource().getLocation() == null) {
                return false;
            }
            String location = URLDecoder.decode(domain.getCodeSource().getLocation().toExternalForm(), StandardCharsets.UTF_8);
            return matchToken(location).contains(patternToken);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String matchToken(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private Pattern tryRegex(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException ignored) {
            return null;
        }
    }

    private String riskLevel(List<Class<?>> targets) {
        for (Class<?> target : targets) {
            String name = target.getName();
            if (name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.")
                    || name.startsWith("net.minecraft.") || name.startsWith("net.minecraftforge.")
                    || name.startsWith("cpw.mods.") || name.startsWith("org.spongepowered.")) {
                return "high";
            }
        }
        return "medium";
    }

    private enum ReturnMode {
        AUTO_DEFAULT("auto_default"),
        VOID_RETURN("void_return"),
        BOOLEAN_FALSE("boolean_false"),
        INT_ZERO("int_zero"),
        REFERENCE_NULL("reference_null");

        private final String configName;

        ReturnMode(String configName) {
            this.configName = configName;
        }

        private static ReturnMode byName(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "void", "void_return" -> VOID_RETURN;
                case "boolean", "bool", "false", "boolean_false" -> BOOLEAN_FALSE;
                case "int", "integer", "zero", "int_zero" -> INT_ZERO;
                case "reference", "object", "null", "reference_null" -> REFERENCE_NULL;
                default -> AUTO_DEFAULT;
            };
        }

        private boolean supports(String returnDescriptor) {
            return switch (this) {
                case AUTO_DEFAULT -> isJvmReturnDescriptor(returnDescriptor);
                case VOID_RETURN -> "V".equals(returnDescriptor);
                case BOOLEAN_FALSE -> "Z".equals(returnDescriptor);
                case INT_ZERO -> "I".equals(returnDescriptor);
                case REFERENCE_NULL -> returnDescriptor.startsWith("L") || returnDescriptor.startsWith("[");
            };
        }

        private static boolean isJvmReturnDescriptor(String descriptor) {
            if (descriptor == null || descriptor.isEmpty()) {
                return false;
            }
            char type = descriptor.charAt(0);
            return type == 'V' || type == 'Z' || type == 'B' || type == 'C' || type == 'S' || type == 'I'
                    || type == 'J' || type == 'F' || type == 'D';
        }
    }

    private static final class DefaultReturnTransformer implements ClassFileTransformer {
        private final List<Class<?>> targets;
        private final String methodPattern;
        private final Pattern methodRegex;
        private final ReturnMode returnMode;
        private int transformInvocations;
        private int transformedMethods;

        private DefaultReturnTransformer(List<Class<?>> targets, String methodPattern, ReturnMode returnMode) {
            this.targets = List.copyOf(targets);
            this.methodPattern = methodPattern == null ? "" : methodPattern.trim();
            this.methodRegex = compileMethodRegex(this.methodPattern);
            this.returnMode = returnMode;
        }

        int transformedMethods() {
            return transformedMethods;
        }

        int transformInvocations() {
            return transformInvocations;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
            return transformTarget(classBeingRedefined, classfileBuffer);
        }

        @Override
        public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
            return transformTarget(classBeingRedefined, classfileBuffer);
        }

        private byte[] transformTarget(Class<?> classBeingRedefined, byte[] classfileBuffer)
                throws IllegalClassFormatException {
            transformInvocations++;
            if (classBeingRedefined == null || !targets.contains(classBeingRedefined)) {
                return null;
            }
            return transformBytes(classfileBuffer);
        }

        private byte[] transformBytes(byte[] classfileBuffer) throws IllegalClassFormatException {
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        String returnDescriptor = returnDescriptor(descriptor);
                        if (shouldTransform(access, name, returnDescriptor)) {
                            transformedMethods++;
                            MethodVisitor replacement = super.visitMethod(access, name, descriptor, signature, exceptions);
                            replacement.visitCode();
                            emitDefaultReturn(replacement, returnDescriptor);
                            replacement.visitMaxs(stackSlots(returnDescriptor), 0);
                            replacement.visitEnd();
                            return null;
                        }
                        return super.visitMethod(access, name, descriptor, signature, exceptions);
                    }
                };
                reader.accept(visitor, 0);
                return writer.toByteArray();
            } catch (Throwable throwable) {
                throw new IllegalClassFormatException(throwable.getMessage());
            }
        }

        private boolean shouldTransform(int access, String name, String returnDescriptor) {
            if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return false;
            }
            if ("<init>".equals(name) || "<clinit>".equals(name)) {
                return false;
            }
            if (!returnMode.supports(returnDescriptor)) {
                return false;
            }
            if (methodPattern.isBlank()) {
                return true;
            }
            return name.contains(methodPattern) || (methodRegex != null && methodRegex.matcher(name).find());
        }

        private static Pattern compileMethodRegex(String pattern) {
            if (pattern == null || pattern.isBlank()) {
                return null;
            }
            try {
                return Pattern.compile(pattern);
            } catch (PatternSyntaxException ignored) {
                return null;
            }
        }

        private static String returnDescriptor(String descriptor) {
            int end = descriptor == null ? -1 : descriptor.lastIndexOf(')');
            if (end < 0 || end + 1 >= descriptor.length()) {
                return "";
            }
            return descriptor.substring(end + 1);
        }

        private static void emitDefaultReturn(MethodVisitor visitor, String returnDescriptor) {
            switch (returnDescriptor) {
                case "V" -> visitor.visitInsn(Opcodes.RETURN);
                case "Z", "B", "C", "S", "I" -> {
                    visitor.visitInsn(Opcodes.ICONST_0);
                    visitor.visitInsn(Opcodes.IRETURN);
                }
                case "J" -> {
                    visitor.visitInsn(Opcodes.LCONST_0);
                    visitor.visitInsn(Opcodes.LRETURN);
                }
                case "F" -> {
                    visitor.visitInsn(Opcodes.FCONST_0);
                    visitor.visitInsn(Opcodes.FRETURN);
                }
                case "D" -> {
                    visitor.visitInsn(Opcodes.DCONST_0);
                    visitor.visitInsn(Opcodes.DRETURN);
                }
                default -> {
                    if (returnDescriptor.startsWith("L") || returnDescriptor.startsWith("[")) {
                        visitor.visitInsn(Opcodes.ACONST_NULL);
                        visitor.visitInsn(Opcodes.ARETURN);
                        return;
                    }
                    throw new IllegalArgumentException("unsupported return descriptor: " + returnDescriptor);
                }
            }
        }

        private static int stackSlots(String returnDescriptor) {
            return "V".equals(returnDescriptor) ? 0
                    : ("J".equals(returnDescriptor) || "D".equals(returnDescriptor) ? 2 : 1);
        }
    }
}
