package com.fangnai.hacker.client.define;

import com.fangnai.hacker.client.ai.tool.ToolRegistry;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.VersionInfo;
import net.minecraftforge.forgespi.language.IModFileInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 运行期名称映射。
 * <p>
 * 正式环境里游戏类的“类名”与开发期一致，但方法名、字段名是混淆后的运行期名称（形如 m_数字_ / f_数字_）。
 * AI 写的源码用的是开发期名称，直接拿运行时的 classpath 编译会找不到类，
 * 即使找到了，方法名也对不上。因此这里负责：
 * <ol>
 *     <li>读取游戏安装时留下的两份映射表，建立“开发期名称 ↔ 运行期名称”的对照；</li>
 *     <li>生成一份“开发期名称”的游戏类存根 jar（只保留签名，不含方法体），作为 javac 的编译 classpath；</li>
 *     <li>编译完成后，把 class 中引用的开发期方法名/字段名改写成运行期名称，再交给 define。</li>
 * </ol>
 * 开发环境（运行期本来就是开发期名称）下以上步骤全部跳过。
 */
public final class RuntimeNameMapper {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RuntimeNameMapper INSTANCE = new RuntimeNameMapper();
    /** 存根格式版本：存根生成逻辑变化时加一，旧缓存会自动重建。 */
    private static final String STUB_FORMAT = "1";
    private static final String[] NO_PARENTS = new String[0];

    /** 映射表中的类定义行，例如：net.minecraft.client.Minecraft -> enn: */
    private static final Pattern CLASS_LINE = Pattern.compile("^(\\S+) -> (\\S+):$");
    /** 映射表中的方法行，例如：1:5:void tick(int,boolean):10:12 -> a */
    private static final Pattern METHOD_LINE = Pattern.compile("^(?:\\d+:\\d+:)?(\\S+) ([^\\s(]+)\\(([^)]*)\\)(?::\\d+(?::\\d+)?)? -> (\\S+)$");
    /** 映射表中的字段行，例如：int tickCount -> b */
    private static final Pattern FIELD_LINE = Pattern.compile("^(\\S+) (\\S+) -> (\\S+)$");
    private static final Pattern CLASS_IN_DESC = Pattern.compile("L([^;]+);");

    private volatile Mappings mappings;
    private volatile GameFiles gameFiles;
    private volatile Path stubJar;
    private volatile String lastError = "";
    /** 类名 -> 父类与接口（内部名），用于沿继承链查找映射。 */
    private final Map<String, String[]> parentCache = new ConcurrentHashMap<>();

    private RuntimeNameMapper() {
    }

    public static RuntimeNameMapper get() {
        return INSTANCE;
    }

    /** 只有运行期使用混淆名的正式环境才需要映射。 */
    public boolean needsRemap() {
        return "srg".equals(FMLEnvironment.naming);
    }

    /** 最近一次准备映射失败的原因（没有则为空字符串）。 */
    public String lastError() {
        return lastError;
    }

    /** 游戏启动时在后台线程提前准备映射与存根，避免第一次编译时卡顿。 */
    public void warmUpAsync() {
        if (!needsRemap()) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                ensureReady();
                LOGGER.info("[Fangnai AI] Runtime name mapping ready in {} ms, stub={}", System.currentTimeMillis() - start, stubJar);
            } catch (Throwable throwable) {
                LOGGER.warn("[Fangnai AI] Runtime name mapping warm-up failed: {}", throwable.toString());
            }
        }, "fangnai-name-mapper");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /** 加载映射表并确保存根 jar 存在（只做一次，结果常驻内存）。 */
    public synchronized void ensureReady() throws IOException {
        if (mappings != null && stubJar != null) {
            return;
        }
        try {
            GameFiles files = locateGameFiles();
            gameFiles = files;
            if (mappings == null) {
                mappings = Mappings.load(files.officialMappings(), files.mergedMappings());
            }
            if (stubJar == null) {
                stubJar = buildStubJar(files);
            }
            lastError = "";
        } catch (IOException | RuntimeException exception) {
            lastError = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            throw exception;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 编译 classpath
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 给 javac 用的 classpath：开发期名称的游戏存根（必须排在最前面）→ 已加载的模组 jar → 启动 classpath 中的依赖库。
     */
    public String compileClasspath() {
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        Set<String> runtimeGameJars = new HashSet<>();
        if (needsRemap()) {
            try {
                ensureReady();
                entries.add(stubJar);
                GameFiles files = gameFiles;
                runtimeGameJars.add(fileName(files.gameJar()));
                runtimeGameJars.add(fileName(files.forgeClient()));
                runtimeGameJars.add(fileName(files.forgeUniversal()));
            } catch (Throwable throwable) {
                LOGGER.warn("[Fangnai AI] Runtime name mapping unavailable, compiling without game stubs: {}", throwable.toString());
            }
        }
        // 已加载的模组（含本模组），让生成代码可以直接引用模组里的类
        try {
            for (IModFileInfo info : ModList.get().getModFiles()) {
                Path path = info.getFile().getFilePath();
                if (isPlainFile(path) && !runtimeGameJars.contains(fileName(path))) {
                    entries.add(path);
                }
            }
        } catch (Throwable throwable) {
            LOGGER.warn("[Fangnai AI] Failed to list mod files for compile classpath: {}", throwable.toString());
        }
        // 启动 classpath 中的第三方库（数学库、集合库、日志等）
        for (String item : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (item.isBlank()) {
                continue;
            }
            try {
                Path path = Path.of(item);
                if (Files.exists(path) && !runtimeGameJars.contains(fileName(path))) {
                    entries.add(path);
                }
            } catch (Exception ignored) {
                // 非法路径直接跳过
            }
        }
        StringBuilder builder = new StringBuilder();
        for (Path entry : entries) {
            if (!builder.isEmpty()) {
                builder.append(File.pathSeparatorChar);
            }
            builder.append(entry.toAbsolutePath());
        }
        return builder.toString();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 编译结果改写为运行期名称
    // ══════════════════════════════════════════════════════════════════════

    /** 把目录下所有编译出的 class 原地改写为运行期名称，返回处理的文件数。 */
    public int remapCompiledDirectory(Path directory) throws IOException {
        if (!needsRemap() || directory == null || !Files.isDirectory(directory)) {
            return 0;
        }
        ensureReady();
        Map<Path, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path path : stream.filter(p -> p.toString().endsWith(".class")).toList()) {
                files.put(path, Files.readAllBytes(path));
            }
        }
        // 先登记本次编译出的所有类的继承关系（它们还没被加载，没法用反射查）
        Map<String, String[]> localParents = new HashMap<>();
        for (byte[] bytes : files.values()) {
            ClassReader reader = new ClassReader(bytes);
            localParents.put(reader.getClassName(), parentsOf(reader.getSuperName(), reader.getInterfaces()));
        }
        for (Map.Entry<Path, byte[]> entry : files.entrySet()) {
            Files.write(entry.getKey(), toRuntimeNames(entry.getValue(), localParents));
        }
        return files.size();
    }

    /** 改写单个 class 的字节码（用于直接提交 class 字节的情况）。 */
    public byte[] toRuntimeNamesIfNeeded(byte[] bytes) throws IOException {
        if (!needsRemap()) {
            return bytes;
        }
        ensureReady();
        ClassReader reader = new ClassReader(bytes);
        Map<String, String[]> localParents = new HashMap<>();
        localParents.put(reader.getClassName(), parentsOf(reader.getSuperName(), reader.getInterfaces()));
        return toRuntimeNames(bytes, localParents);
    }

    private byte[] toRuntimeNames(byte[] bytes, Map<String, String[]> localParents) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, new ToRuntimeNames(localParents)), 0);
        return writer.toByteArray();
    }

    /** 开发期名称 -> 运行期名称：沿继承链在映射表中查找。 */
    private final class ToRuntimeNames extends Remapper {
        private final Map<String, String[]> localParents;

        private ToRuntimeNames(Map<String, String[]> localParents) {
            this.localParents = localParents;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            if (name.startsWith("<") || owner.startsWith("[")) {
                return name;
            }
            String mapped = findMethod(owner, name, descriptor, new HashSet<>(), localParents);
            return mapped == null ? name : mapped;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String mapped = findField(owner, name, new HashSet<>(), localParents);
            return mapped == null ? name : mapped;
        }

        @Override
        public String mapInvokeDynamicMethodName(String name, String descriptor) {
            // lambda / 方法引用实现的函数式接口方法：接口是 invokedynamic 描述符的返回类型
            Type returnType = Type.getReturnType(descriptor);
            if (returnType.getSort() != Type.OBJECT) {
                return name;
            }
            String mapped = findMethodByName(returnType.getInternalName(), name, new HashSet<>(), localParents);
            return mapped == null ? name : mapped;
        }
    }

    private String findMethod(String owner, String name, String descriptor, Set<String> visited, Map<String, String[]> localParents) {
        if (!visited.add(owner)) {
            return null;
        }
        Map<String, String> methods = mappings.methodsByClass.get(owner);
        if (methods != null) {
            String mapped = methods.get(name + descriptor);
            if (mapped != null) {
                return mapped;
            }
        }
        for (String parent : parents(owner, localParents)) {
            String mapped = findMethod(parent, name, descriptor, visited, localParents);
            if (mapped != null) {
                return mapped;
            }
        }
        return null;
    }

    private String findField(String owner, String name, Set<String> visited, Map<String, String[]> localParents) {
        if (!visited.add(owner)) {
            return null;
        }
        Map<String, String> fields = mappings.fieldsByClass.get(owner);
        if (fields != null) {
            String mapped = fields.get(name);
            if (mapped != null) {
                return mapped;
            }
        }
        for (String parent : parents(owner, localParents)) {
            String mapped = findField(parent, name, visited, localParents);
            if (mapped != null) {
                return mapped;
            }
        }
        return null;
    }

    /** 只按方法名查找（函数式接口通常只有一个同名抽象方法）。 */
    private String findMethodByName(String owner, String name, Set<String> visited, Map<String, String[]> localParents) {
        if (!visited.add(owner)) {
            return null;
        }
        Map<String, String> methods = mappings.methodsByClass.get(owner);
        if (methods != null) {
            String prefix = name + "(";
            for (Map.Entry<String, String> entry : methods.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    return entry.getValue();
                }
            }
        }
        for (String parent : parents(owner, localParents)) {
            String mapped = findMethodByName(parent, name, visited, localParents);
            if (mapped != null) {
                return mapped;
            }
        }
        return null;
    }

    private String[] parents(String owner, Map<String, String[]> localParents) {
        String[] local = localParents.get(owner);
        if (local != null) {
            return local;
        }
        if (owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jdk/") || owner.startsWith("sun/")) {
            return NO_PARENTS;
        }
        return parentCache.computeIfAbsent(owner, this::loadParents);
    }

    /** 游戏中的类名在开发期和运行期一致，可以直接用当前类加载器反查父类与接口（不触发类初始化）。 */
    private String[] loadParents(String owner) {
        try {
            Class<?> type = Class.forName(owner.replace('/', '.'), false, RuntimeNameMapper.class.getClassLoader());
            List<String> result = new ArrayList<>();
            if (type.getSuperclass() != null) {
                result.add(Type.getInternalName(type.getSuperclass()));
            }
            for (Class<?> anInterface : type.getInterfaces()) {
                result.add(Type.getInternalName(anInterface));
            }
            return result.toArray(NO_PARENTS);
        } catch (Throwable throwable) {
            return NO_PARENTS;
        }
    }

    private static String[] parentsOf(String superName, String[] interfaces) {
        List<String> result = new ArrayList<>();
        if (superName != null) {
            result.add(superName);
        }
        if (interfaces != null) {
            result.addAll(List.of(interfaces));
        }
        return result.toArray(NO_PARENTS);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 开发期名称存根 jar
    // ══════════════════════════════════════════════════════════════════════

    private Path buildStubJar(GameFiles files) throws IOException {
        Path directory = ToolRegistry.get().root().resolve("cache").resolve("compile");
        Files.createDirectories(directory);
        Path output = directory.resolve("game-dev-names-" + files.versionTag() + ".jar");
        Path keyFile = directory.resolve(output.getFileName() + ".key");
        String key = "format=" + STUB_FORMAT
                + "\ngame=" + fileStamp(files.gameJar())
                + "\nforgeClient=" + fileStamp(files.forgeClient())
                + "\nforgeUniversal=" + fileStamp(files.forgeUniversal());
        if (Files.isRegularFile(output) && Files.isRegularFile(keyFile)
                && key.equals(Files.readString(keyFile, StandardCharsets.UTF_8))) {
            return output;
        }

        long start = System.currentTimeMillis();
        // 后读的 jar 覆盖先读的：打过补丁的游戏类覆盖原版类
        Map<String, byte[]> classes = new LinkedHashMap<>();
        readClasses(files.gameJar(), classes);
        readClasses(files.forgeClient(), classes);
        readClasses(files.forgeUniversal(), classes);

        Remapper toDevNames = new Remapper() {
            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                return mappings.runtimeToOfficial.getOrDefault(name, name);
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                return mappings.runtimeToOfficial.getOrDefault(name, name);
            }
        };

        Path temp = directory.resolve(output.getFileName() + ".tmp");
        try (OutputStream stream = Files.newOutputStream(temp);
             JarOutputStream jar = new JarOutputStream(stream)) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                ClassReader reader = new ClassReader(entry.getValue());
                ClassWriter writer = new ClassWriter(0);
                // 存根只给编译器看签名，跳过方法体可以让文件小很多、生成更快
                reader.accept(new ClassRemapper(writer, toDevNames),
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(writer.toByteArray());
                jar.closeEntry();
            }
        }
        Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(keyFile, key, StandardCharsets.UTF_8);
        LOGGER.info("[Fangnai AI] Built dev-name compile stub with {} classes in {} ms: {}",
                classes.size(), System.currentTimeMillis() - start, output);
        return output;
    }

    private static void readClasses(Path jarPath, Map<String, byte[]> classes) throws IOException {
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            return;
        }
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || name.startsWith("META-INF/")
                        || name.endsWith("module-info.class")) {
                    continue;
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    classes.put(name, input.readAllBytes());
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 定位游戏文件
    // ══════════════════════════════════════════════════════════════════════

    private GameFiles locateGameFiles() throws IOException {
        VersionInfo version = FMLLoader.versionInfo();
        Path libraries = libraryDirectory();
        String mcAndMappingVersion = version.mcVersion() + "-" + version.mcpVersion();
        Path clientDir = libraries.resolve("net").resolve("minecraft").resolve("client").resolve(mcAndMappingVersion);
        Path gameJar = clientDir.resolve("client-" + mcAndMappingVersion + "-srg.jar");
        Path officialMappings = clientDir.resolve("client-" + mcAndMappingVersion + "-mappings.txt");
        Path mergedMappings = libraries.resolve("de").resolve("oceanlabs").resolve("mcp").resolve("mcp_config")
                .resolve(mcAndMappingVersion).resolve("mcp_config-" + mcAndMappingVersion + "-mappings-merged.txt");
        String mcAndForgeVersion = version.mcVersion() + "-" + version.forgeVersion();
        Path forgeDir = libraries;
        for (String part : version.forgeGroup().split("\\.")) {
            forgeDir = forgeDir.resolve(part);
        }
        forgeDir = forgeDir.resolve("forge").resolve(mcAndForgeVersion);
        Path forgeClient = forgeDir.resolve("forge-" + mcAndForgeVersion + "-client.jar");
        Path forgeUniversal = forgeDir.resolve("forge-" + mcAndForgeVersion + "-universal.jar");
        for (Path required : List.of(gameJar, officialMappings, mergedMappings)) {
            if (!Files.isRegularFile(required)) {
                throw new IOException("缺少编译所需的游戏文件：" + required);
            }
        }
        return new GameFiles(gameJar, officialMappings, mergedMappings, forgeClient, forgeUniversal, mcAndForgeVersion);
    }

    private static Path libraryDirectory() throws IOException {
        String property = System.getProperty("libraryDirectory");
        if (property != null && !property.isBlank()) {
            Path path = Path.of(property);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        // 兜底：从游戏目录往上找 libraries 文件夹
        Path directory = FMLPaths.GAMEDIR.get().toAbsolutePath();
        for (int i = 0; i < 5 && directory != null; i++, directory = directory.getParent()) {
            Path candidate = directory.resolve("libraries");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IOException("找不到游戏 libraries 目录");
    }

    private static boolean isPlainFile(Path path) {
        return path != null && path.getFileSystem() == FileSystems.getDefault() && Files.isRegularFile(path);
    }

    private static String fileName(Path path) {
        return path == null || path.getFileName() == null ? "" : path.getFileName().toString();
    }

    private static String fileStamp(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            return "missing";
        }
        return Files.size(path) + "@" + Files.getLastModifiedTime(path).toMillis();
    }

    private record GameFiles(Path gameJar, Path officialMappings, Path mergedMappings,
                             Path forgeClient, Path forgeUniversal, String versionTag) {
    }

    // ══════════════════════════════════════════════════════════════════════
    // 映射表
    // ══════════════════════════════════════════════════════════════════════

    /** 只保存“开发期名称与运行期名称不同”的条目，减少内存占用。 */
    private static final class Mappings {
        /** 开发期类内部名 -> 字段开发期名 -> 运行期名 */
        private final Map<String, Map<String, String>> fieldsByClass = new HashMap<>();
        /** 开发期类内部名 -> 方法开发期名+描述符 -> 运行期名 */
        private final Map<String, Map<String, String>> methodsByClass = new HashMap<>();
        /** 运行期名 -> 开发期名（运行期名全局唯一，覆写方法共用同一个名字） */
        private final Map<String, String> runtimeToOfficial = new HashMap<>();

        /**
         * @param officialFile 开发期名称 -> 混淆名 的映射（安装时下载）
         * @param mergedFile   混淆名 -> 运行期名称 的映射（安装时生成）
         */
        private static Mappings load(Path officialFile, Path mergedFile) throws IOException {
            long start = System.currentTimeMillis();
            Mappings result = new Mappings();

            // 第一步：混淆名 -> 运行期名称
            Map<String, Map<String, String>> obfFieldToRuntime = new HashMap<>();
            Map<String, Map<String, String>> obfMethodToRuntime = new HashMap<>();
            Map<String, String> currentFields = null;
            Map<String, String> currentMethods = null;
            for (String line : Files.readAllLines(mergedFile, StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.startsWith("tsrg") || line.startsWith("\t\t")) {
                    continue;
                }
                if (line.charAt(0) != '\t') {
                    String[] parts = line.split(" ");
                    currentFields = obfFieldToRuntime.computeIfAbsent(parts[0], key -> new HashMap<>());
                    currentMethods = obfMethodToRuntime.computeIfAbsent(parts[0], key -> new HashMap<>());
                    continue;
                }
                if (currentFields == null) {
                    continue;
                }
                String[] parts = line.substring(1).split(" ");
                if (parts.length == 2) {
                    currentFields.put(parts[0], parts[1]);
                } else if (parts.length == 3 && parts[1].startsWith("(")) {
                    currentMethods.put(parts[0] + parts[1], parts[2]);
                } else if (parts.length == 3) {
                    currentFields.put(parts[0], parts[2]);
                }
            }

            // 第二步：开发期名称 -> 混淆名，再拼接得到 开发期名称 -> 运行期名称
            List<String> lines = Files.readAllLines(officialFile, StandardCharsets.UTF_8);
            Map<String, String> officialToObfClass = new HashMap<>();
            for (String line : lines) {
                if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == ' ') {
                    continue;
                }
                Matcher matcher = CLASS_LINE.matcher(line);
                if (matcher.matches()) {
                    officialToObfClass.put(matcher.group(1).replace('.', '/'), matcher.group(2).replace('.', '/'));
                }
            }
            String officialClass = null;
            String obfClass = null;
            for (String line : lines) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                if (line.charAt(0) != ' ') {
                    Matcher matcher = CLASS_LINE.matcher(line);
                    if (matcher.matches()) {
                        officialClass = matcher.group(1).replace('.', '/');
                        obfClass = matcher.group(2).replace('.', '/');
                    } else {
                        officialClass = null;
                    }
                    continue;
                }
                if (officialClass == null) {
                    continue;
                }
                String member = line.trim();
                if (member.startsWith("#")) {
                    continue;
                }
                Matcher method = METHOD_LINE.matcher(member);
                if (method.matches()) {
                    String name = method.group(2);
                    if (name.startsWith("<")) {
                        continue;
                    }
                    String descriptor = methodDescriptor(method.group(1), method.group(3));
                    Map<String, String> obfMethods = obfMethodToRuntime.get(obfClass);
                    String runtime = obfMethods == null ? null
                            : obfMethods.get(method.group(4) + toObfDescriptor(descriptor, officialToObfClass));
                    if (runtime != null && !runtime.equals(name)) {
                        result.methodsByClass.computeIfAbsent(officialClass, key -> new HashMap<>()).put(name + descriptor, runtime);
                        result.runtimeToOfficial.put(runtime, name);
                    }
                    continue;
                }
                Matcher field = FIELD_LINE.matcher(member);
                if (field.matches()) {
                    String name = field.group(2);
                    Map<String, String> obfFields = obfFieldToRuntime.get(obfClass);
                    String runtime = obfFields == null ? null : obfFields.get(field.group(3));
                    if (runtime != null && !runtime.equals(name)) {
                        result.fieldsByClass.computeIfAbsent(officialClass, key -> new HashMap<>()).put(name, runtime);
                        result.runtimeToOfficial.put(runtime, name);
                    }
                }
            }
            LOGGER.info("[Fangnai AI] Loaded runtime name mappings: classes={}, names={} in {} ms",
                    officialToObfClass.size(), result.runtimeToOfficial.size(), System.currentTimeMillis() - start);
            return result;
        }

        private static String methodDescriptor(String returnType, String parameters) {
            StringBuilder builder = new StringBuilder("(");
            if (!parameters.isBlank()) {
                for (String parameter : parameters.split(",")) {
                    builder.append(typeDescriptor(parameter.trim()));
                }
            }
            return builder.append(')').append(typeDescriptor(returnType)).toString();
        }

        private static String typeDescriptor(String javaType) {
            String type = javaType;
            int dimensions = 0;
            while (type.endsWith("[]")) {
                dimensions++;
                type = type.substring(0, type.length() - 2);
            }
            String base = switch (type) {
                case "void" -> "V";
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "char" -> "C";
                case "short" -> "S";
                case "int" -> "I";
                case "long" -> "J";
                case "float" -> "F";
                case "double" -> "D";
                default -> "L" + type.replace('.', '/') + ";";
            };
            return "[".repeat(dimensions) + base;
        }

        private static String toObfDescriptor(String descriptor, Map<String, String> officialToObfClass) {
            Matcher matcher = CLASS_IN_DESC.matcher(descriptor);
            StringBuilder builder = new StringBuilder();
            while (matcher.find()) {
                String obf = officialToObfClass.getOrDefault(matcher.group(1), matcher.group(1));
                matcher.appendReplacement(builder, Matcher.quoteReplacement("L" + obf + ";"));
            }
            matcher.appendTail(builder);
            return builder.toString();
        }
    }
}
