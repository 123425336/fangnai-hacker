package com.fangnai.hacker.client.ai.tool;

import com.fangnai.hacker.client.command.ChatFeedback;
import com.fangnai.hacker.client.config.HackerClientConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

public final class JarAnalysisService {
    private static final JarAnalysisService INSTANCE = new JarAnalysisService();
    /** 已加载的类以运行时为准：读取真实的成员结构/字节码，而不是磁盘上的 jar。 */
    private final RuntimeClassInspector runtime = RuntimeClassInspector.get();

    private JarAnalysisService() {
    }

    public static JarAnalysisService get() {
        return INSTANCE;
    }

    public void analyze(String mode, String jarSelector, String classPattern, String memberPattern,
                        String searchText, String reason, String expectedEffect) {
        ChatFeedback.info("开始执行 jar 分析：" + normalizeMode(mode) + "。输出将限制并保存到 tool/jar-analysis。");
        analyzeAsync(mode, jarSelector, classPattern, memberPattern, searchText, reason, expectedEffect)
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        ChatFeedback.error("jar 分析失败：" + throwable.getMessage());
                        return;
                    }
                    if (result == null || !result.success()) {
                        ChatFeedback.error("jar 分析失败：" + (result == null ? "未知错误" : result.errorMessage()));
                        return;
                    }
                    String preview = truncate(result.content(), Math.min(1200, HackerClientConfig.ai().jarAnalysisMaxOutputChars));
                    ChatFeedback.info("jar 分析完成：" + result.title() + "\n" + preview + "\n完整输出：" + result.outputPath());
                });
    }

    public CompletableFuture<AnalysisRunResult> analyzeAsync(String mode, String jarSelector, String classPattern,
                                                            String memberPattern, String searchText,
                                                            String reason, String expectedEffect) {
        String normalizedMode = normalizeMode(mode);
        return CompletableFuture.supplyAsync(() -> {
            try {
                AnalysisResult result = doAnalyze(normalizedMode, jarSelector, classPattern, memberPattern, searchText);
                Path output = saveOutput(result, reason, expectedEffect);
                boolean truncated = result.content().contains("... 已截断 ...");
                return new AnalysisRunResult(true, normalizedMode, result.title(), result.content(), output, "", truncated);
            } catch (Exception e) {
                return new AnalysisRunResult(false, normalizedMode, "jar analysis failed", "", null,
                        e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()), false);
            }
        });
    }

    private AnalysisResult doAnalyze(String rawMode, String jarSelector, String classPattern, String memberPattern,
                                     String searchText) throws Exception {
        String mode = normalizeMode(rawMode);
        return switch (mode) {
            case "list_jars" -> listJars();
            case "list_entries" -> listEntries(resolveJar(jarSelector));
            case "manifest" -> manifest(resolveJar(jarSelector));
            // jarSelector 留空时自动在整个 mods 目录里搜索，避免 AI 不知道类在哪个 jar 时直接报错
            case "find_class" -> isBlank(jarSelector)
                    ? findClassInAllJars(firstNonBlank(classPattern, searchText))
                    : findClass(resolveJar(jarSelector), firstNonBlank(classPattern, searchText));
            // javap_class：类已加载时优先读运行时版本，并和 jar 对比；没加载或没有 agent 时退回读 jar
            case "javap_class" -> javapAuto(jarSelector, classPattern, false);
            case "runtime_javap" -> javapAuto(jarSelector, classPattern, true);
            case "runtime_members" -> runtimeMembers(jarSelector, classPattern, memberPattern);
            default -> throw new IllegalArgumentException("不支持的 jar 分析模式：" + rawMode);
        };
    }

    private AnalysisResult listJars() throws Exception {
        Path mods = realModsDir();
        List<Path> jars = jarsInMods(mods);
        if (jars.isEmpty()) {
            return new AnalysisResult("mods jar 列表", "mods 目录没有 jar：" + mods);
        }
        StringBuilder builder = new StringBuilder("mods 目录：").append(mods).append('\n');
        for (Path jar : jars) {
            builder.append("- ").append(mods.relativize(jar)).append(" (").append(Files.size(jar)).append(" bytes)\n");
        }
        return new AnalysisResult("mods jar 列表", builder.toString());
    }

    private AnalysisResult listEntries(Path jar) throws Exception {
        HackerClientConfig.AiSettings ai = HackerClientConfig.ai();
        StringBuilder builder = new StringBuilder("Jar: ").append(jar.getFileName()).append('\n');
        int count = 0;
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements() && count < ai.jarAnalysisMaxEntries) {
                JarEntry entry = entries.nextElement();
                builder.append(entry.getName()).append('\n');
                count++;
            }
            if (entries.hasMoreElements()) {
                builder.append("... 已达到条目上限 ").append(ai.jarAnalysisMaxEntries).append('\n');
            }
        }
        return new AnalysisResult("jar entries: " + jar.getFileName(), limitOutput(builder.toString()));
    }

    private AnalysisResult manifest(Path jar) throws Exception {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return new AnalysisResult("manifest: " + jar.getFileName(), "该 jar 没有 META-INF/MANIFEST.MF");
            }
            StringBuilder builder = new StringBuilder("Jar: ").append(jar.getFileName()).append("\n\n");
            manifest.getMainAttributes().forEach((key, value) ->
                    builder.append(key).append(": ").append(value).append('\n'));
            return new AnalysisResult("manifest: " + jar.getFileName(), limitOutput(builder.toString()));
        }
    }

    private AnalysisResult findClass(Path jar, String query) throws Exception {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("find_class 需要 classPattern 或 searchText。可先 list_entries。 ");
        }
        String normalized = normalizeClassQuery(query);
        String lower = normalized.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            var entries = jarFile.entries();
            int scanned = 0;
            while (entries.hasMoreElements() && scanned < HackerClientConfig.ai().jarAnalysisMaxEntries) {
                JarEntry entry = entries.nextElement();
                scanned++;
                String name = entry.getName();
                if (!entry.isDirectory() && name.endsWith(".class")) {
                    String className = entryToClassName(name);
                    if (className.toLowerCase(Locale.ROOT).contains(lower)) {
                        matches.add(className);
                    }
                }
            }
        }
        StringBuilder builder = new StringBuilder("Jar: ").append(jar.getFileName()).append('\n')
                .append("Query: ").append(query).append('\n')
                .append("Matches: ").append(matches.size()).append('\n');
        matches.stream().sorted().limit(200).forEach(match -> builder.append("- ").append(match).append('\n'));
        if (matches.size() > 200) {
            builder.append("... 仅显示前 200 个匹配，请缩小 classPattern。\n");
        }
        return new AnalysisResult("find class: " + jar.getFileName(), limitOutput(builder.toString()));
    }

    /** 在 mods 目录的所有 jar 中查找类名包含关键字的类，结果按 jar 分组输出。 */
    private AnalysisResult findClassInAllJars(String query) throws Exception {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("find_class 需要 classPattern 或 searchText。");
        }
        String lower = normalizeClassQuery(query).toLowerCase(Locale.ROOT);
        Path mods = realModsDir();
        List<Path> jars = jarsInMods(mods);
        StringBuilder builder = new StringBuilder("Scope: mods 目录全部 jar（").append(jars.size()).append(" 个）\n")
                .append("Query: ").append(query).append('\n');
        int total = 0;
        int jarHits = 0;
        for (Path jar : jars) {
            List<String> matches = matchClasses(jar, lower);
            if (matches.isEmpty()) {
                continue;
            }
            jarHits++;
            builder.append("\nJar: ").append(jar.getFileName()).append("（").append(matches.size()).append(" 个匹配）\n");
            for (String match : matches.stream().sorted().limit(Math.max(0, 200 - total)).toList()) {
                builder.append("- ").append(match).append('\n');
                total++;
            }
            if (total >= 200) {
                builder.append("... 仅显示前 200 个匹配，请缩小 classPattern 或指定 jarSelector。\n");
                break;
            }
        }
        builder.insert(0, "Matches: " + total + "，命中 jar: " + jarHits + "\n");
        if (total == 0) {
            builder.append("\n没有任何 jar 包含匹配的类。可换一个关键字（如英文名、注册名片段）再搜。\n");
        }
        return new AnalysisResult("find class: 全部 mods", limitOutput(builder.toString()));
    }

    /** 根据类名定位所在 jar：先按精确类名找，再按包含关键字找；只有唯一 jar 命中时才返回。 */
    private Path locateJarForClass(String classPattern) throws Exception {
        if (classPattern == null || classPattern.isBlank()) {
            throw new IllegalArgumentException("javap_class 需要 classPattern。可先使用 find_class（可不填 jarSelector）查找准确类名。");
        }
        String normalized = normalizeClassQuery(classPattern);
        String exactEntry = normalized.replace('.', '/') + ".class";
        Path mods = realModsDir();
        List<Path> jars = jarsInMods(mods);
        List<Path> exactJars = new ArrayList<>();
        for (Path jar : jars) {
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                if (jarFile.getEntry(exactEntry) != null) {
                    exactJars.add(jar);
                }
            } catch (Exception ignored) {
                // 损坏或无法读取的 jar 直接跳过
            }
        }
        if (exactJars.size() == 1) {
            return exactJars.get(0);
        }
        List<Path> fuzzyJars = new ArrayList<>();
        if (exactJars.isEmpty()) {
            String lower = normalized.toLowerCase(Locale.ROOT);
            for (Path jar : jars) {
                if (!matchClasses(jar, lower).isEmpty()) {
                    fuzzyJars.add(jar);
                }
            }
            if (fuzzyJars.size() == 1) {
                return fuzzyJars.get(0);
            }
        }
        List<Path> candidates = exactJars.isEmpty() ? fuzzyJars : exactJars;
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("mods 目录所有 jar 中都没有找到类：" + classPattern + "。可先用 find_class 搜索关键字。");
        }
        StringBuilder builder = new StringBuilder("类 " + classPattern + " 出现在多个 jar 中，请指定 jarSelector：");
        candidates.stream().limit(10).forEach(path -> builder.append("\n- ").append(path.getFileName()));
        throw new IllegalArgumentException(builder.toString());
    }

    /** 扫描单个 jar，返回类名（小写比较）包含关键字的全部类。 */
    private List<String> matchClasses(Path jar, String lowerQuery) {
        List<String> matches = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            var entries = jarFile.entries();
            int scanned = 0;
            while (entries.hasMoreElements() && scanned < HackerClientConfig.ai().jarAnalysisMaxEntries) {
                JarEntry entry = entries.nextElement();
                scanned++;
                String name = entry.getName();
                if (!entry.isDirectory() && name.endsWith(".class")) {
                    String className = entryToClassName(name);
                    if (className.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                        matches.add(className);
                    }
                }
            }
        } catch (Exception ignored) {
            // 无法读取的 jar 不影响其余 jar 的搜索
        }
        return matches;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private AnalysisResult javapClass(Path jar, String classPattern) throws Exception {
        if (classPattern == null || classPattern.isBlank()) {
            throw new IllegalArgumentException("javap_class 需要 classPattern。可先使用 find_class 查找准确类名。");
        }
        String className = resolveSingleClass(jar, classPattern);
        Path javap = resolveJavap();
        return runJavap("javap: " + className, List.of(javap.toString(), "-classpath", jar.toString(), "-c", "-p", className));
    }

    /** 执行 javap 命令并限制输出长度与耗时。 */
    private AnalysisResult runJavap(String title, List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        CompletableFuture<CapturedOutput> reader = CompletableFuture.supplyAsync(() -> readLimited(process.getInputStream(), HackerClientConfig.ai().jarAnalysisMaxOutputChars));
        boolean completed = process.waitFor(HackerClientConfig.ai().jarAnalysisTimeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("javap 超时，已终止进程。title=" + title);
        }
        CapturedOutput output = reader.get(2, TimeUnit.SECONDS);
        String text = "Command: " + String.join(" ", command) + "\nExit: " + process.exitValue() + "\n\n" + output.text();
        if (output.truncated()) {
            text += "\n... 输出已按 jarAnalysisMaxOutputChars 截断。\n";
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("javap 退出码 " + process.exitValue() + "：" + truncate(output.text(), 800));
        }
        return new AnalysisResult(title, limitOutput(text));
    }

    /**
     * javap_class / runtime_javap：已加载的类优先读取运行时版本（抓取运行时字节码后反汇编，并和 jar 对比差异）。
     * 类没有加载或 agent 不可用时：javap_class 退回读 jar 静态文件，runtime_javap（runtimeOnly）直接报错。
     */
    private AnalysisResult javapAuto(String jarSelector, String classPattern, boolean runtimeOnly) throws Exception {
        if (isBlank(classPattern)) {
            throw new IllegalArgumentException("javap_class 需要 classPattern。可先使用 find_class（可不填 jarSelector）查找准确类名。");
        }
        RuntimeResolution resolved = resolveForRuntime(jarSelector, classPattern);
        if (resolved.lookup().found().isEmpty()) {
            if (resolved.staticJar() == null) {
                // 运行时和 jar 里都找不到：保持原有的报错内容
                throw new IllegalArgumentException(resolved.staticError().isBlank()
                        ? runtimeNotFound(resolved.name(), resolved.lookup())
                        : (runtimeOnly ? runtimeNotFound(resolved.name(), resolved.lookup()) + " 按 jar 解析类名也失败：" : "") + resolved.staticError());
            }
            if (runtimeOnly) {
                throw new IllegalArgumentException(runtimeNotFound(resolved.name(), resolved.lookup()));
            }
            AnalysisResult fallback = javapClass(resolved.staticJar(), classPattern);
            return new AnalysisResult(fallback.title(), "来源：jar 静态文件。" + runtimeNotFound(resolved.name(), resolved.lookup())
                    + "下面是 jar 里的版本，可能与游戏里实际运行的不同。\n\n" + fallback.content());
        }

        RuntimeClassInspector.LoadedClass loaded = resolved.lookup().found().get(0);
        Class<?> type = loaded.type();
        StringBuilder header = new StringBuilder();
        header.append("类：").append(type.getName()).append('\n');
        header.append("加载状态：").append(loaded.origin()).append("；加载器：")
                .append(RuntimeClassInspector.loaderName(type.getClassLoader())).append('\n');
        if (resolved.lookup().found().size() > 1) {
            header.append("注意：同名类还存在于 ").append(resolved.lookup().found().size() - 1)
                    .append(" 个其他加载器中，这里读取的是第 1 个（模组/游戏主加载器优先）。\n");
        }
        byte[] bytes = null;
        String captureError = "";
        try {
            bytes = runtime.captureRuntimeBytes(type);
        } catch (Throwable throwable) {
            captureError = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
        if (bytes == null && runtimeOnly) {
            throw new IllegalStateException("无法抓取运行时字节码：" + captureError
                    + "。可改用 mode=runtime_members 读取运行时成员（不需要 agent）。");
        }
        header.append(bytes != null
                ? "来源：运行时字节码（对已加载的类做一次不改动字节码的 retransform，抓取 JVM 当前持有的版本）\n"
                : "来源：运行时反射（抓取运行时字节码失败：" + captureError + "），下面只有成员结构，没有方法体\n");
        Path jar = findJarForLoadedClass(type, jarSelector);
        header.append('\n').append(jarComparison(type, bytes, jar));

        if (bytes != null) {
            Path file = runtime.saveRuntimeBytes(type, bytes);
            AnalysisResult javap = runJavap("javap(runtime): " + type.getName(),
                    List.of(resolveJavap().toString(), "-c", "-p", file.toString()));
            return new AnalysisResult(javap.title(), limitOutput(header + "\n" + javap.content()));
        }
        StringBuilder text = new StringBuilder(header).append('\n').append(runtime.describeMembers(loaded, ""));
        if (jar != null) {
            // 没有 agent 时拿不到运行时方法体，附上 jar 里的 javap 作为参考
            AnalysisResult staticVersion = runJavap("javap: " + type.getName(),
                    List.of(resolveJavap().toString(), "-classpath", jar.toString(), "-c", "-p", type.getName()));
            text.append("\n附：下面是 jar 静态文件里的方法体，可能与运行时不同：\n").append(staticVersion.content());
        }
        return new AnalysisResult("javap(runtime-members): " + type.getName(), limitOutput(text.toString()));
    }

    /** runtime_members：用反射读取已加载类的真实成员（无副作用，不需要 agent），并和 jar 对比。 */
    private AnalysisResult runtimeMembers(String jarSelector, String classPattern, String memberPattern) throws Exception {
        if (isBlank(classPattern)) {
            throw new IllegalArgumentException("runtime_members 需要 classPattern（完整类名最稳妥）。");
        }
        RuntimeResolution resolved = resolveForRuntime(jarSelector, classPattern);
        if (resolved.lookup().found().isEmpty()) {
            throw new IllegalArgumentException(runtimeNotFound(resolved.name(), resolved.lookup())
                    + (resolved.staticError().isBlank() ? "" : " 按 jar 解析类名也失败：" + resolved.staticError()));
        }
        List<RuntimeClassInspector.LoadedClass> list = resolved.lookup().found();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < Math.min(3, list.size()); i++) {
            RuntimeClassInspector.LoadedClass loaded = list.get(i);
            if (i > 0) {
                builder.append('\n').append("-".repeat(40)).append("\n\n");
            }
            builder.append(runtime.describeMembers(loaded, memberPattern)).append('\n');
            builder.append(jarComparison(loaded.type(), null, findJarForLoadedClass(loaded.type(), jarSelector)));
        }
        if (list.size() > 3) {
            builder.append("\n... 另有 ").append(list.size() - 3).append(" 个同名类未显示。\n");
        }
        return new AnalysisResult("runtime members: " + list.get(0).type().getName(), limitOutput(builder.toString()));
    }

    /** 把 classPattern 解析成运行时的类：先按完整类名找；找不到再按 jar 里的唯一匹配补全类名后重试。 */
    private RuntimeResolution resolveForRuntime(String jarSelector, String classPattern) {
        String normalized = normalizeClassQuery(classPattern);
        RuntimeClassInspector.Lookup lookup = runtime.find(normalized);
        Path staticJar = null;
        String staticError = "";
        if (lookup.found().isEmpty()) {
            try {
                Path jar = isBlank(jarSelector) ? locateJarForClass(classPattern) : resolveJar(jarSelector);
                String fullName = resolveSingleClass(jar, classPattern);
                staticJar = jar;
                if (!fullName.equals(normalized)) {
                    lookup = runtime.find(fullName);
                }
            } catch (Exception e) {
                staticError = String.valueOf(e.getMessage());
            }
        }
        return new RuntimeResolution(normalized, lookup, staticJar, staticError);
    }

    private static String runtimeNotFound(String name, RuntimeClassInspector.Lookup lookup) {
        return "运行时没有找到类 " + name + "（既没有被游戏加载，也无法按需加载"
                + (lookup.failure().isBlank() ? "" : "：" + lookup.failure()) + "）。";
    }

    /** 找出 mods 目录里包含这个已加载类的 jar：先看调用方指定的，再看类的代码来源，最后逐个 jar 查找。 */
    private Path findJarForLoadedClass(Class<?> type, String jarSelector) {
        String entry = type.getName().replace('.', '/') + ".class";
        try {
            Path mods = realModsDir();
            if (!isBlank(jarSelector)) {
                Path selected = resolveJar(jarSelector);
                if (jarHasEntry(selected, entry)) {
                    return selected;
                }
            }
            Path source = RuntimeClassInspector.codeSourcePath(type);
            if (source != null && source.startsWith(mods) && jarHasEntry(source, entry)) {
                return source;
            }
            for (Path jar : jarsInMods(mods)) {
                if (jarHasEntry(jar, entry)) {
                    return jar;
                }
            }
        } catch (Exception ignored) {
            // 找不到 jar 不影响运行时分析，只是少了对比
        }
        return null;
    }

    private static boolean jarHasEntry(Path jar, String entry) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            return jarFile.getEntry(entry) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static byte[] readJarClass(Path jar, String className) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(className.replace('.', '/') + ".class");
            if (entry == null) {
                return null;
            }
            try (InputStream input = jarFile.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    /** 对比运行时版本和 jar 版本：字节码是否一致，不一致时列出成员差异。runtimeBytes 为 null 时用反射得到的成员对比。 */
    private String jarComparison(Class<?> type, byte[] runtimeBytes, Path jar) {
        if (jar == null) {
            return "与 jar 的对比：mods 目录里没有 jar 包含这个类（可能是游戏本体、加载器或其他类库里的类），无 jar 可对比。\n";
        }
        StringBuilder builder = new StringBuilder("与 jar 的对比（").append(jar.getFileName()).append("）：");
        try {
            byte[] jarBytes = readJarClass(jar, type.getName());
            if (jarBytes == null) {
                return builder.append("jar 里读不到这个类。\n").toString();
            }
            if (runtimeBytes != null && Arrays.equals(runtimeBytes, jarBytes)) {
                return builder.append("运行时字节码与 jar 逐字节一致，这个类没有被任何转换器改写，jar 里看到的就是运行时的样子。\n").toString();
            }
            RuntimeClassInspector.MemberSet jarSet = RuntimeClassInspector.memberSetFromBytes(jarBytes);
            RuntimeClassInspector.MemberSet runtimeSet = runtimeBytes != null
                    ? RuntimeClassInspector.memberSetFromBytes(runtimeBytes)
                    : RuntimeClassInspector.memberSetFromReflection(type);
            if (runtimeBytes == null) {
                builder.append('\n').append(RuntimeClassInspector.diffMembers(runtimeSet, jarSet));
                return builder.toString();
            }
            // 加载器重新序列化类时常常只改变方法排序和调试信息，所以要比成员和方法体指令，不能只看字节是否相同
            java.util.Map<String, String> runtimeBodies = RuntimeClassInspector.methodBodyHashes(runtimeBytes);
            java.util.Map<String, String> jarBodies = RuntimeClassInspector.methodBodyHashes(jarBytes);
            List<String> changedBodies = RuntimeClassInspector.changedMethodBodies(runtimeBodies, jarBodies);
            boolean sameMembers = RuntimeClassInspector.sameMembers(runtimeSet, jarSet);
            if (sameMembers && changedBodies.isEmpty() && runtimeBodies != null && jarBodies != null) {
                builder.append("字节码逐字节不同（运行时 ").append(runtimeBytes.length).append(" 字节，jar ").append(jarBytes.length)
                        .append(" 字节），但成员和所有方法体指令都一致，只是方法排序、调试信息等被重新序列化，可视为没有实质改写。\n");
                return builder.toString();
            }
            builder.append("运行时与 jar 存在实质差异（运行时 ").append(runtimeBytes.length).append(" 字节，jar ").append(jarBytes.length)
                    .append(" 字节）：类在加载后被转换器改写过，或 jar 与运行中的版本不是同一个。\n");
            builder.append(RuntimeClassInspector.diffMembers(runtimeSet, jarSet));
            builder.append(RuntimeClassInspector.describeChangedBodies(changedBodies));
        } catch (Exception e) {
            builder.append("对比失败：").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
        }
        return builder.toString();
    }

    private Path resolveJar(String selector) throws Exception {
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("该模式需要 jarSelector。可先用 list_jars 查看 mods 目录。 ");
        }
        Path mods = realModsDir();
        String trimmed = selector.trim();
        Path direct = Path.of(trimmed);
        if (!direct.isAbsolute()) {
            direct = mods.resolve(trimmed);
        }
        if (Files.exists(direct)) {
            return validateJar(direct, mods);
        }

        String needle = trimmed.toLowerCase(Locale.ROOT);
        List<Path> matches = jarsInMods(mods).stream()
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("mods 目录未找到匹配 jar：" + selector);
        }
        if (matches.size() > 1) {
            StringBuilder builder = new StringBuilder("jarSelector 匹配多个 jar，请更精确：");
            matches.stream().limit(10).forEach(path -> builder.append("\n- ").append(path.getFileName()));
            throw new IllegalArgumentException(builder.toString());
        }
        return validateJar(matches.get(0), mods);
    }

    private Path validateJar(Path candidate, Path realMods) throws Exception {
        Path real = candidate.toRealPath();
        if (!real.startsWith(realMods)) {
            throw new SecurityException("拒绝访问 mods 目录外文件：" + candidate);
        }
        if (!Files.isRegularFile(real)) {
            throw new IllegalArgumentException("不是普通文件：" + candidate);
        }
        if (!real.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IllegalArgumentException("只允许分析 .jar 文件：" + candidate);
        }
        long maxBytes = HackerClientConfig.ai().jarAnalysisMaxJarMegabytes * 1024L * 1024L;
        if (Files.size(real) > maxBytes) {
            throw new IllegalArgumentException("jar 超过大小限制 " + HackerClientConfig.ai().jarAnalysisMaxJarMegabytes + " MiB：" + real.getFileName());
        }
        return real;
    }

    private Path realModsDir() throws Exception {
        Path mods = FMLPaths.GAMEDIR.get().resolve("mods").normalize();
        if (!Files.isDirectory(mods)) {
            throw new IllegalStateException("mods 目录不存在：" + mods);
        }
        return mods.toRealPath();
    }

    private List<Path> jarsInMods(Path realMods) throws Exception {
        try (Stream<Path> stream = Files.list(realMods)) {
            return stream.filter(path -> {
                        try {
                            Path real = path.toRealPath();
                            return real.startsWith(realMods)
                                    && Files.isRegularFile(real)
                                    && real.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                                    && Files.size(real) <= HackerClientConfig.ai().jarAnalysisMaxJarMegabytes * 1024L * 1024L;
                        } catch (Exception ignored) {
                            return false;
                        }
                    })
                    .map(path -> {
                        try {
                            return path.toRealPath();
                        } catch (Exception ignored) {
                            return path.toAbsolutePath().normalize();
                        }
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private String resolveSingleClass(Path jar, String pattern) throws Exception {
        String normalized = normalizeClassQuery(pattern);
        String exactEntry = normalized.replace('.', '/') + ".class";
        List<String> matches = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            if (jarFile.getEntry(exactEntry) != null) {
                return normalized;
            }
            var entries = jarFile.entries();
            int scanned = 0;
            String lower = normalized.toLowerCase(Locale.ROOT);
            while (entries.hasMoreElements() && scanned < HackerClientConfig.ai().jarAnalysisMaxEntries) {
                JarEntry entry = entries.nextElement();
                scanned++;
                String name = entry.getName();
                if (!entry.isDirectory() && name.endsWith(".class")) {
                    String className = entryToClassName(name);
                    if (className.toLowerCase(Locale.ROOT).contains(lower)) {
                        matches.add(className);
                    }
                }
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("未找到 classPattern 匹配类：" + pattern);
        }
        if (matches.size() > 1) {
            StringBuilder builder = new StringBuilder("classPattern 匹配多个类，请改为精确类名：");
            matches.stream().sorted().limit(20).forEach(match -> builder.append("\n- ").append(match));
            throw new IllegalArgumentException(builder.toString());
        }
        return matches.get(0);
    }

    private Path resolveJavap() throws Exception {
        String executable = isWindows() ? "javap.exe" : "javap";
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path javap = javaHome.resolve("bin").resolve(executable);
        if (Files.isRegularFile(javap)) {
            return javap;
        }
        Path parentJavap = javaHome.getParent() == null ? null : javaHome.getParent().resolve("bin").resolve(executable);
        if (parentJavap != null && Files.isRegularFile(parentJavap)) {
            return parentJavap;
        }
        throw new IllegalStateException("未找到 JDK javap，可安装 JDK 或把游戏运行时指向 JDK。java.home=" + javaHome);
    }

    private Path saveOutput(AnalysisResult result, String reason, String expectedEffect) throws Exception {
        Path dir = ToolRegistry.get().root().resolve("jar-analysis");
        Files.createDirectories(dir);
        String fileName = sanitize(result.title()) + "-" + Instant.now().toString().replace(':', '-').replace('.', '-') + ".md";
        Path output = dir.resolve(fileName);
        String text = "# " + result.title() + "\n\n"
                + "- Time: " + Instant.now() + "\n"
                + "- Reason: " + reason + "\n"
                + "- Expected effect: " + expectedEffect + "\n\n"
                + "```text\n" + result.content() + "\n```\n";
        Files.writeString(output, text, StandardCharsets.UTF_8);
        return output;
    }

    private String limitOutput(String value) {
        return truncate(value, HackerClientConfig.ai().jarAnalysisMaxOutputChars);
    }

    private static CapturedOutput readLimited(InputStream input, int maxChars) {
        int maxBytes = Math.max(1024, maxChars * 4);
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 65536));
        boolean truncated = false;
        try (input) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int remaining = maxBytes - output.size();
                if (remaining > 0) {
                    output.write(buffer, 0, Math.min(read, remaining));
                }
                if (read > remaining) {
                    truncated = true;
                }
            }
        } catch (Exception e) {
            return new CapturedOutput("读取进程输出失败：" + e.getMessage(), false);
        }
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        return new CapturedOutput(text, truncated);
    }

    private static String normalizeMode(String mode) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", "list", "list_jars" -> "list_jars";
            case "entries", "list_entries" -> "list_entries";
            case "manifest", "inspect_manifest" -> "manifest";
            case "find", "find_class", "search_class" -> "find_class";
            case "javap", "javap_class", "decompile", "decompile_class" -> "javap_class";
            case "runtime_members", "runtime", "members", "runtime_class" -> "runtime_members";
            case "runtime_javap", "runtime_bytecode", "runtime_bytes" -> "runtime_javap";
            default -> normalized;
        };
    }

    private static String normalizeClassQuery(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(0, normalized.length() - ".class".length());
        }
        return normalized.replace('/', '.').replace('\\', '.');
    }

    private static String entryToClassName(String entry) {
        return entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String sanitize(String value) {
        String sanitized = value == null ? "jar-analysis" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        sanitized = sanitized.replaceAll("^-+|-+$", "");
        return sanitized.isBlank() ? "jar-analysis" : sanitized.substring(0, Math.min(60, sanitized.length()));
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars)) + "\n... 已截断 ...";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public record AnalysisRunResult(boolean success, String mode, String title, String content, Path outputPath,
                                    String errorMessage, boolean truncated) {
    }

    private record AnalysisResult(String title, String content) {
    }

    /** 运行时类查找结果：name 是规范化后的类名，staticJar/staticError 是按 jar 静态解析的结果（找运行时类失败时才会解析）。 */
    private record RuntimeResolution(String name, RuntimeClassInspector.Lookup lookup, Path staticJar, String staticError) {
    }

    private record CapturedOutput(String text, boolean truncated) {
    }
}
