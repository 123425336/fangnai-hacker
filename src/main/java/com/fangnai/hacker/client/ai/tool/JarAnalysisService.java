package com.fangnai.hacker.client.ai.tool;

import com.fangnai.hacker.client.command.ChatFeedback;
import com.fangnai.hacker.client.config.HackerClientConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
                AnalysisResult result = doAnalyze(normalizedMode, jarSelector, classPattern, searchText);
                Path output = saveOutput(result, reason, expectedEffect);
                boolean truncated = result.content().contains("... 已截断 ...");
                return new AnalysisRunResult(true, normalizedMode, result.title(), result.content(), output, "", truncated);
            } catch (Exception e) {
                return new AnalysisRunResult(false, normalizedMode, "jar analysis failed", "", null,
                        e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()), false);
            }
        });
    }

    private AnalysisResult doAnalyze(String rawMode, String jarSelector, String classPattern, String searchText) throws Exception {
        String mode = normalizeMode(rawMode);
        return switch (mode) {
            case "list_jars" -> listJars();
            case "list_entries" -> listEntries(resolveJar(jarSelector));
            case "manifest" -> manifest(resolveJar(jarSelector));
            case "find_class" -> findClass(resolveJar(jarSelector), firstNonBlank(classPattern, searchText));
            case "javap_class" -> javapClass(resolveJar(jarSelector), classPattern);
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

    private AnalysisResult javapClass(Path jar, String classPattern) throws Exception {
        if (classPattern == null || classPattern.isBlank()) {
            throw new IllegalArgumentException("javap_class 需要 classPattern。可先使用 find_class 查找准确类名。");
        }
        String className = resolveSingleClass(jar, classPattern);
        Path javap = resolveJavap();
        List<String> command = List.of(javap.toString(), "-classpath", jar.toString(), "-c", "-p", className);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        CompletableFuture<CapturedOutput> reader = CompletableFuture.supplyAsync(() -> readLimited(process.getInputStream(), HackerClientConfig.ai().jarAnalysisMaxOutputChars));
        boolean completed = process.waitFor(HackerClientConfig.ai().jarAnalysisTimeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("javap 超时，已终止进程。class=" + className);
        }
        CapturedOutput output = reader.get(2, TimeUnit.SECONDS);
        String text = "Command: " + String.join(" ", command) + "\nExit: " + process.exitValue() + "\n\n" + output.text();
        if (output.truncated()) {
            text += "\n... 输出已按 jarAnalysisMaxOutputChars 截断。\n";
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("javap 退出码 " + process.exitValue() + "：" + truncate(output.text(), 800));
        }
        return new AnalysisResult("javap: " + className, limitOutput(text));
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

    private record CapturedOutput(String text, boolean truncated) {
    }
}
