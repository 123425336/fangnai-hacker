package com.fangnai.hacker.client.define;

import com.fangnai.hacker.client.ai.tool.ToolRegistry;
import com.fangnai.hacker.client.command.ChatFeedback;
import com.fangnai.hacker.client.config.HackerClientConfig;
import com.fangnai.hacker.client.instrument.InstrumentationAccess;
import com.fangnai.hacker.generated.GeneratedClassAnchor;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ClassDefineService {
    private static final ClassDefineService INSTANCE = new ClassDefineService();
    private static final int JAVA_17_MAJOR = 61;
    private volatile IsolatedGeneratedClassLoader lastIsolatedLoader;

    private ClassDefineService() {
    }

    public static ClassDefineService get() {
        return INSTANCE;
    }

    public void define(String className, String base64ClassBytes, String expectedSha256, String reason, String expectedEffect) {
        try {
            byte[] bytes = Base64.getDecoder().decode((base64ClassBytes == null ? "" : base64ClassBytes).replaceAll("\\s+", ""));
            DefineInput input = validateClassBytes(className, bytes, expectedSha256);
            DefineOutcome outcome = defineOrRedefineValidated(input);
            Path classFile = saveClass(input, reason, expectedEffect, outcome);
            ToolRegistry.get().recordDefinedClass(input.className(), classFile, input.sha256(), reason, expectedEffect, outcome.runtimeStatus());
            reportDefineOutcome("class bytes", input.className(), classFile, null, outcome);
        } catch (Throwable throwable) {
            ChatFeedback.error("define class 失败：" + throwable.getMessage());
        }
    }

    public void compileAndDefine(String className, String javaSource, String sourceSha256, String reason, String expectedEffect) {
        reportCompileAndDefineResult(compileAndDefineResult(className, javaSource, sourceSha256, reason, expectedEffect));
    }

    public ClassDefineResult compileAndDefineResult(String className, String javaSource, String sourceSha256,
                                                    String reason, String expectedEffect) {
        String normalizedClassName = className == null ? "" : className.trim().replace('/', '.');
        Path sourcePath = null;
        Path diagnosticsPath = null;
        try {
            normalizedClassName = normalizeAndValidateClassName(className);
            validateJavaSource(normalizedClassName, javaSource, sourceSha256);

            sourcePath = sourcePath(normalizedClassName);
            Path compiledDir = ToolRegistry.get().root().resolve("classes").resolve("compiled").resolve(sanitize(normalizedClassName));
            diagnosticsPath = ToolRegistry.get().root().resolve("docs")
                    .resolve("compile-" + sanitize(normalizedClassName) + ".diagnostics.txt");
            Files.createDirectories(sourcePath.getParent());
            deleteDirectoryContents(compiledDir);
            Files.createDirectories(compiledDir);
            Files.createDirectories(diagnosticsPath.getParent());
            Files.writeString(sourcePath, javaSource, StandardCharsets.UTF_8);

            Path compiledClass = compiledDir.resolve(normalizedClassName.replace('.', '/') + ".class");
            CompileResult compileResult = compileSource(sourcePath, compiledDir);
            Files.writeString(diagnosticsPath, compileResult.diagnostics(), StandardCharsets.UTF_8);
            if (!compileResult.success()) {
                return new ClassDefineResult(false, true, normalizedClassName,
                        "Java 源码编译失败，诊断已保存到 " + diagnosticsPath,
                        compileResult.diagnostics(), sourcePath, diagnosticsPath, null, "compile_failed");
            }

            if (!Files.isRegularFile(compiledClass)) {
                return new ClassDefineResult(false, true, normalizedClassName,
                        "Java 源码编译结束但未找到目标 class 文件：" + compiledClass,
                        compileResult.diagnostics(), sourcePath, diagnosticsPath, null, "compile_missing_class");
            }
            List<DefineInput> inputs = compiledClassInputs(compiledDir, normalizedClassName);
            DefineBatchOutcome batchOutcome = defineOrRedefineValidatedBatch(inputs, normalizedClassName);
            DefineInput primaryInput = batchOutcome.primaryInput();
            DefineOutcome outcome = batchOutcome.primaryOutcome();
            Map<String, Path> classFiles = saveClasses(inputs, reason, expectedEffect, batchOutcome.outcomes());
            Path classFile = classFiles.get(primaryInput.className());
            ToolRegistry.get().recordCompiledDefinedClass(primaryInput.className(), sourcePath, classFile, diagnosticsPath,
                    sha256(javaSource.getBytes(StandardCharsets.UTF_8)), primaryInput.sha256(), reason, expectedEffect, outcome.runtimeStatus());
            return new ClassDefineResult(true, false, primaryInput.className(),
                    compileDefineMessage("源码编译", primaryInput.className(), classFile, sourcePath, outcome, inputs.size()),
                    compileResult.diagnostics(), sourcePath, diagnosticsPath, classFile, outcome.runtimeStatus());
        } catch (Throwable throwable) {
            return new ClassDefineResult(false, true, normalizedClassName,
                    "Java 源码编译/校验失败：" + throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage()),
                    stackMessage(throwable), sourcePath, diagnosticsPath, null, "failed");
        }
    }

    public CompileCheckResult checkJavaSourceCompiles(String className, String javaSource, String sourceSha256) {
        String normalizedClassName = className == null ? "" : className.trim().replace('/', '.');
        Path sourcePath = null;
        Path diagnosticsPath = null;
        try {
            normalizedClassName = normalizeAndValidateClassName(className);
            validateJavaSource(normalizedClassName, javaSource, sourceSha256);
            Path sourceRoot = ToolRegistry.get().root().resolve("classes").resolve("preflight").resolve("source");
            Path compiledDir = ToolRegistry.get().root().resolve("classes").resolve("preflight").resolve("compiled").resolve(sanitize(normalizedClassName));
            sourcePath = sourceRoot.resolve(normalizedClassName.replace('.', '/') + ".java");
            diagnosticsPath = ToolRegistry.get().root().resolve("docs")
                    .resolve("preflight-" + sanitize(normalizedClassName) + ".diagnostics.txt");
            Files.createDirectories(sourcePath.getParent());
            deleteDirectoryContents(compiledDir);
            Files.createDirectories(compiledDir);
            Files.createDirectories(diagnosticsPath.getParent());
            Files.writeString(sourcePath, javaSource, StandardCharsets.UTF_8);
            Path compiledClass = compiledDir.resolve(normalizedClassName.replace('.', '/') + ".class");
            CompileResult compileResult = compileSource(sourcePath, compiledDir);
            Files.writeString(diagnosticsPath, compileResult.diagnostics(), StandardCharsets.UTF_8);
            if (!compileResult.success()) {
                return new CompileCheckResult(false, true, normalizedClassName,
                        "Java 源码预编译失败，诊断已保存到 " + diagnosticsPath,
                        compileResult.diagnostics(), sourcePath, diagnosticsPath);
            }
            if (!Files.isRegularFile(compiledClass)) {
                return new CompileCheckResult(false, true, normalizedClassName,
                        "Java 源码预编译结束但未找到目标 class 文件：" + compiledClass,
                        compileResult.diagnostics(), sourcePath, diagnosticsPath);
            }
            byte[] bytes = Files.readAllBytes(compiledClass);
            validateClassBytes(normalizedClassName, bytes, "");
            return new CompileCheckResult(true, false, normalizedClassName,
                    "Java 源码预编译成功。", compileResult.diagnostics(), sourcePath, diagnosticsPath);
        } catch (Throwable throwable) {
            return new CompileCheckResult(false, true, normalizedClassName,
                    "Java 源码预编译/校验失败：" + throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage()),
                    stackMessage(throwable), sourcePath, diagnosticsPath);
        }
    }

    public void reportCompileAndDefineResult(ClassDefineResult result) {
        if (result == null) {
            ChatFeedback.error("编译/define Java class 失败：未知错误。");
            return;
        }
        if (result.success()) {
            if ("saved_restart_required".equals(result.runtimeStatus())) {
                ChatFeedback.warn(result.message());
            } else {
                ChatFeedback.info(result.message());
            }
            return;
        }
        String diagnostics = result.diagnostics() == null || result.diagnostics().isBlank() ? "" : "：\n" + truncate(result.diagnostics(), 1200);
        ChatFeedback.error(result.message() + diagnostics);
    }

    public Class<?> loadSavedClass(String className, Path classFile, String expectedSha256) throws Exception {
        String normalizedClassName = normalizeAndValidateClassName(className);
        if (classFile == null || !Files.isRegularFile(classFile)) {
            throw new IllegalArgumentException("保存的 class 文件不存在：" + classFile);
        }
        List<DefineInput> inputs = savedClassInputs(normalizedClassName, classFile, expectedSha256);
        DefineBatchOutcome batchOutcome = defineOrRedefineValidatedBatch(inputs, normalizedClassName);
        DefineOutcome outcome = batchOutcome.primaryOutcome();
        if (outcome.status() == DefineStatus.SAVED_RESTART_REQUIRED) {
            ChatFeedback.warn("保存工具类已读取新版字节码，但当前 JVM 未更新：" + outcome.detail());
        }
        return outcome.runtimeClass();
    }

    public void defineSavedClass(String className, Path classFile, String expectedSha256, String reason, String expectedEffect) {
        try {
            String normalizedClassName = normalizeAndValidateClassName(className);
            Class<?> loaded = loadSavedClass(normalizedClassName, classFile, expectedSha256);
            if (loaded.getName().equals(normalizedClassName)) {
                ChatFeedback.info("保存工具类已加载/更新：" + loaded.getName() + "；class=" + classFile + "；原因=" + reason + "；效果=" + expectedEffect);
            }
        } catch (Throwable throwable) {
            ChatFeedback.error("保存工具 define class 失败：" + throwable.getMessage());
        }
    }

    private CompileResult compileSource(Path sourcePath, Path compiledDir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("当前运行时不是 JDK，无法本地编译 Java 源码；请使用带 javac 的 JDK 运行游戏。");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fangnai-java-compile");
            thread.setDaemon(true);
            return thread;
        });
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(compiledDir));
            Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromPaths(List.of(sourcePath));
            List<String> options = new ArrayList<>();
            options.add("-encoding");
            options.add("UTF-8");
            options.add("-proc:none");
            options.add("-classpath");
            options.add(System.getProperty("java.class.path", ""));
            Callable<Boolean> callable = () -> compiler.getTask(null, fileManager, diagnostics, options, null, sources).call();
            Future<Boolean> future = executor.submit(callable);
            boolean success = future.get(HackerClientConfig.ai().compileDefineTimeoutSeconds, TimeUnit.SECONDS);
            return new CompileResult(success, diagnosticsToString(diagnostics));
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new IllegalStateException("Java 源码编译超时（" + HackerClientConfig.ai().compileDefineTimeoutSeconds + "s）。");
        } finally {
            executor.shutdownNow();
        }
    }

    private String diagnosticsToString(DiagnosticCollector<JavaFileObject> diagnostics) {
        if (diagnostics.getDiagnostics().isEmpty()) {
            return "(no compiler diagnostics)";
        }
        StringBuilder builder = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            builder.append(diagnostic.getKind())
                    .append(" line=").append(diagnostic.getLineNumber())
                    .append(" col=").append(diagnostic.getColumnNumber())
                    .append(": ").append(diagnostic.getMessage(Locale.ROOT))
                    .append('\n');
        }
        return builder.toString();
    }

    private void validateJavaSource(String className, String javaSource, String sourceSha256) throws Exception {
        if (javaSource == null || javaSource.isBlank()) {
            throw new IllegalArgumentException("javaSource 不能为空。");
        }
        if (javaSource.length() > HackerClientConfig.ai().generatedSourceMaxChars) {
            throw new IllegalArgumentException("javaSource 超过限制：" + javaSource.length() + " > " + HackerClientConfig.ai().generatedSourceMaxChars);
        }
        if (sourceSha256 != null && !sourceSha256.isBlank()) {
            String actual = sha256(javaSource.getBytes(StandardCharsets.UTF_8));
            if (!actual.equalsIgnoreCase(sourceSha256.trim())) {
                throw new IllegalArgumentException("sourceSha256 不匹配：expected=" + sourceSha256 + ", actual=" + actual);
            }
        }
        String packageName = packageName(className);
        String simpleName = simpleName(className);
        if (!javaSource.contains("package " + packageName + ";")) {
            throw new IllegalArgumentException("Java 源码必须声明 package " + packageName + ";");
        }
        if (!javaSource.contains("class " + simpleName) && !javaSource.contains("record " + simpleName)
                && !javaSource.contains("interface " + simpleName) && !javaSource.contains("enum " + simpleName)) {
            throw new IllegalArgumentException("Java 源码中未找到匹配的顶层类型名：" + simpleName);
        }
    }

    private DefineInput validateClassBytes(String declaredClassName, byte[] bytes, String expectedSha256) throws Exception {
        String normalizedDeclared = normalizeAndValidateClassName(declaredClassName);
        if (bytes.length > HackerClientConfig.ai().classDefineMaxBytes) {
            throw new IllegalArgumentException("class bytes 超过限制：" + bytes.length + " > " + HackerClientConfig.ai().classDefineMaxBytes);
        }
        if (bytes.length < 16) {
            throw new IllegalArgumentException("class bytes 过短。");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int magic = buffer.getInt();
        if (magic != 0xCAFEBABE) {
            throw new IllegalArgumentException("不是有效 classfile：magic 不匹配。");
        }
        int minor = Short.toUnsignedInt(buffer.getShort());
        int major = Short.toUnsignedInt(buffer.getShort());
        if (major > JAVA_17_MAJOR) {
            throw new IllegalArgumentException("classfile 版本过高：major=" + major + "，当前只允许 Java 17 及以下。");
        }
        rejectForbiddenPackage(normalizedDeclared);
        String actualName = readInternalClassName(bytes).replace('/', '.');
        if (!normalizedDeclared.equals(actualName)) {
            throw new IllegalArgumentException("声明 className 与 classfile 内部名称不一致：declared=" + normalizedDeclared + ", actual=" + actualName);
        }
        String hash = sha256(bytes);
        if (expectedSha256 != null && !expectedSha256.isBlank() && !hash.equalsIgnoreCase(expectedSha256.trim())) {
            throw new IllegalArgumentException("sha256 不匹配：expected=" + expectedSha256 + ", actual=" + hash);
        }
        return new DefineInput(normalizedDeclared, bytes, hash, major, minor);
    }

    private DefineOutcome defineOrRedefineValidated(DefineInput input) throws IllegalAccessException {
        Class<?> existing = findLoadedGeneratedClass(input.className());
        if (existing == null) {
            Class<?> defined = defineValidated(input);
            return new DefineOutcome(defined, DefineStatus.DEFINED, "新类已 define；重启游戏可清除该定义。");
        }

        // 先尝试 instrumentation redefine（只有在方法体变化时才能成功，结构改变时会失败）
        Instrumentation instrumentation = InstrumentationAccess.getInstrumentation();
        if (instrumentation != null && instrumentation.isRedefineClassesSupported()
                && instrumentation.isModifiableClass(existing)) {
            try {
                instrumentation.redefineClasses(new ClassDefinition(existing, input.bytes()));
                return new DefineOutcome(existing, DefineStatus.REDEFINED,
                        "已 live redefine 当前 JVM 中的同名类（instrumentation）；旧栈帧可能要等当前方法返回后才切换到新版字节码。");
            } catch (Throwable throwable) {
                // instrumentation redefine 失败（常见：新增/删除字段或方法），回退到隔离 ClassLoader 方式
                ChatFeedback.warn("instrumentation redefine 失败，将使用隔离 ClassLoader 加载新版本："
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
        }

        // 使用隔离 ClassLoader define 新版本
        // 旧版 Class 对象在所有引用解除后会被 GC
        return defineWithIsolatedLoader(input);
    }

    private synchronized DefineOutcome defineWithIsolatedLoader(DefineInput input) {
        String allowedPackage = HackerClientConfig.ai().classDefineAllowedPackage;
        IsolatedGeneratedClassLoader loader = new IsolatedGeneratedClassLoader(
                GeneratedClassAnchor.class.getClassLoader(), allowedPackage);
        try {
            Class<?> defined = loader.defineIsolatedClass(input.className(), input.bytes());
            String oldLoaderInfo = lastIsolatedLoader != null ? "，旧 loader=" + lastIsolatedLoader.toShortString() : "";
            lastIsolatedLoader = loader;
            return new DefineOutcome(defined, DefineStatus.REDEFINED_ISOLATED,
                    "已用新隔离 ClassLoader 加载新版本" + oldLoaderInfo
                            + "；旧版 Class 对象在解除所有引用后将被 GC；loader=" + loader.toShortString()
                            + "。注意：持有旧版 Class 引用的代码（如反射缓存）需要更新到新版。");
        } catch (Exception e) {
            return restartRequired(findLoadedGeneratedClass(input.className()),
                    "隔离 ClassLoader define 失败：" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private DefineBatchOutcome defineOrRedefineValidatedBatch(List<DefineInput> inputs, String primaryClassName) throws IllegalAccessException {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("没有可 define 的 class bytes。");
        }
        DefineInput primaryInput = null;
        List<DefineInput> ordered = new ArrayList<>(inputs);
        ordered.sort(Comparator.comparingInt(input -> dependencyOrder(input.className(), primaryClassName)));
        Map<String, DefineOutcome> outcomes = new LinkedHashMap<>();
        for (DefineInput input : ordered) {
            if (input.className().equals(primaryClassName)) {
                primaryInput = input;
            }
            outcomes.put(input.className(), defineOrRedefineValidated(input));
        }
        if (primaryInput == null) {
            throw new IllegalArgumentException("编译产物中缺少主类：" + primaryClassName);
        }
        return new DefineBatchOutcome(primaryInput, outcomes.get(primaryInput.className()), outcomes);
    }

    private static int dependencyOrder(String className, String primaryClassName) {
        return primaryClassName != null && primaryClassName.equals(className) ? 1 : 0;
    }

    private List<DefineInput> compiledClassInputs(Path compiledDir, String primaryClassName) throws Exception {
        Path packageDir = compiledDir.resolve(packageName(primaryClassName).replace('.', '/'));
        return classInputsNearPrimary(packageDir, primaryClassName, "");
    }

    private List<DefineInput> savedClassInputs(String primaryClassName, Path primaryClassFile, String expectedSha256) throws Exception {
        Path classDir = primaryClassFile.toAbsolutePath().normalize().getParent();
        return classInputsNearPrimary(classDir, primaryClassName, expectedSha256);
    }

    private List<DefineInput> classInputsNearPrimary(Path classDir, String primaryClassName, String expectedPrimarySha256) throws Exception {
        String packagePrefix = packageName(primaryClassName) + ".";
        List<DefineInput> inputs = new ArrayList<>();
        try (var stream = Files.list(classDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            byte[] bytes = Files.readAllBytes(path);
                            String actualName = readInternalClassName(bytes).replace('/', '.');
                            String actualSimpleName = simpleName(actualName);
                            if (!actualName.equals(primaryClassName)
                                    && !(actualName.startsWith(packagePrefix) && actualSimpleName.startsWith(simpleName(primaryClassName) + "$"))) {
                                return;
                            }
                            String expectedSha256 = actualName.equals(primaryClassName) ? expectedPrimarySha256 : "";
                            inputs.add(validateClassBytes(actualName, bytes, expectedSha256));
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        } catch (RuntimeException exception) {
                            throw exception;
                        } catch (Exception exception) {
                            throw new IllegalArgumentException("读取 class 失败：" + path + "：" + exception.getMessage(), exception);
                        }
                    });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
        if (inputs.stream().noneMatch(input -> input.className().equals(primaryClassName))) {
            throw new IllegalArgumentException("缺少主类 class 文件：" + primaryClassName);
        }
        return inputs;
    }

    private void deleteDirectoryContents(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(directory))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private DefineOutcome restartRequired(Class<?> existing, String detail) {
        return new DefineOutcome(existing, DefineStatus.SAVED_RESTART_REQUIRED, detail + "；已保存新版 class，当前 JVM 仍使用已加载旧版。");
    }

    private Class<?> findLoadedGeneratedClass(String className) {
        // 先在主 ClassLoader 找
        try {
            return Class.forName(className, false, GeneratedClassAnchor.class.getClassLoader());
        } catch (ClassNotFoundException ignored) {
        }
        // 再在最近的隔离 ClassLoader 中找
        IsolatedGeneratedClassLoader isolated = lastIsolatedLoader;
        if (isolated != null) {
            try {
                return Class.forName(className, false, isolated);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
    }

    private Class<?> defineValidated(DefineInput input) throws IllegalAccessException {
        return MethodHandles.privateLookupIn(GeneratedClassAnchor.class, MethodHandles.lookup())
                .defineClass(input.bytes());
    }

    private void reportDefineOutcome(String operation, String className, Path classFile, Path sourcePath, DefineOutcome outcome) {
        String message = compileDefineMessage(operation, className, classFile, sourcePath, outcome, 1);
        if (outcome.status() == DefineStatus.SAVED_RESTART_REQUIRED) {
            ChatFeedback.warn(message);
        } else {
            ChatFeedback.info(message);
        }
    }

    private String compileDefineMessage(String operation, String className, Path classFile, Path sourcePath, DefineOutcome outcome) {
        return compileDefineMessage(operation, className, classFile, sourcePath, outcome, 1);
    }

    private String compileDefineMessage(String operation, String className, Path classFile, Path sourcePath, DefineOutcome outcome, int classCount) {
        String location = sourcePath == null ? "class=" + classFile : "source=" + sourcePath + "；class=" + classFile;
        String dependencies = classCount > 1 ? "；已同步 define/redefine 依赖/内部类 " + (classCount - 1) + " 个" : "";
        return operation + " " + outcome.actionText() + "：" + className + "；" + location + dependencies + "；" + outcome.detail();
    }

    private String normalizeAndValidateClassName(String declaredClassName) {
        if (declaredClassName == null || declaredClassName.isBlank()) {
            throw new IllegalArgumentException("className 不能为空。");
        }
        String normalized = declaredClassName.trim().replace('/', '.');
        String allowedPackage = HackerClientConfig.ai().classDefineAllowedPackage;
        String packageName = packageName(normalized);
        String simpleName = simpleName(normalized);
        if (!allowedPackage.equals(packageName)) {
            throw new SecurityException("只允许 define 精确包 " + allowedPackage + "，拒绝：" + normalized);
        }
        if (!isJavaIdentifier(simpleName)) {
            throw new IllegalArgumentException("非法简单类名：" + simpleName);
        }
        return normalized;
    }

    private void rejectForbiddenPackage(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        if (lower.startsWith("java.") || lower.startsWith("javax.") || lower.startsWith("sun.")
                || lower.startsWith("jdk.") || lower.startsWith("net.minecraft.")
                || lower.startsWith("net.minecraftforge.") || lower.startsWith("cpw.mods.")
                || lower.startsWith("org.spongepowered.")) {
            throw new SecurityException("拒绝 define 高风险/核心包：" + className);
        }
    }

    private Path sourcePath(String className) {
        return ToolRegistry.get().root().resolve("classes").resolve("source")
                .resolve(className.replace('.', '/') + ".java");
    }

    private Path saveClass(DefineInput input, String reason, String expectedEffect, DefineOutcome outcome) throws Exception {
        Path classPath = ToolRegistry.get().root().resolve("classes")
                .resolve(input.className().replace('.', '/') + ".class");
        Files.createDirectories(classPath.getParent());
        Files.write(classPath, input.bytes());

        Path docPath = ToolRegistry.get().root().resolve("docs")
                .resolve("defined-" + sanitize(input.className()) + ".md");
        Files.createDirectories(docPath.getParent());
        String doc = "# Defined class " + input.className() + "\n\n"
                + "- Time: " + Instant.now() + "\n"
                + "- Runtime class: `" + outcome.runtimeClass().getName() + "`\n"
                + "- Runtime update: `" + outcome.runtimeStatus() + "`\n"
                + "- Runtime note: " + outcome.detail() + "\n"
                + "- Major/minor: " + input.major() + "/" + input.minor() + "\n"
                + "- SHA-256: `" + input.sha256() + "`\n"
                + "- Reason: " + reason + "\n"
                + "- Expected effect: " + expectedEffect + "\n"
                + "- Note: same class name overwrites saved bytes; live JVM redefine requires schema-compatible changes. Restart clears/loads definitions.\n";
        Files.writeString(docPath, doc, StandardCharsets.UTF_8);
        return classPath;
    }

    private Map<String, Path> saveClasses(List<DefineInput> inputs, String reason, String expectedEffect,
                                          Map<String, DefineOutcome> outcomes) throws Exception {
        Map<String, Path> classFiles = new LinkedHashMap<>();
        for (DefineInput input : inputs) {
            DefineOutcome outcome = outcomes.get(input.className());
            if (outcome == null) {
                throw new IllegalStateException("缺少 define 结果：" + input.className());
            }
            classFiles.put(input.className(), saveClass(input, reason, expectedEffect, outcome));
        }
        return classFiles;
    }

    private static String readInternalClassName(byte[] bytes) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.position(8);
        int constantPoolCount = Short.toUnsignedInt(buffer.getShort());
        Object[] pool = new Object[constantPoolCount];
        for (int i = 1; i < constantPoolCount; i++) {
            int tag = Byte.toUnsignedInt(buffer.get());
            switch (tag) {
                case 1 -> {
                    int length = Short.toUnsignedInt(buffer.getShort());
                    byte[] data = new byte[length];
                    buffer.get(data);
                    pool[i] = new String(data, StandardCharsets.UTF_8);
                }
                case 7 -> pool[i] = Short.toUnsignedInt(buffer.getShort());
                case 3, 4 -> buffer.position(buffer.position() + 4);
                case 5, 6 -> {
                    buffer.position(buffer.position() + 8);
                    i++;
                }
                case 8, 16, 19, 20 -> buffer.position(buffer.position() + 2);
                case 9, 10, 11, 12, 18 -> buffer.position(buffer.position() + 4);
                case 15 -> buffer.position(buffer.position() + 3);
                case 17 -> buffer.position(buffer.position() + 4);
                default -> throw new IllegalArgumentException("不支持的 constant pool tag：" + tag);
            }
        }
        buffer.getShort();
        int thisClassIndex = Short.toUnsignedInt(buffer.getShort());
        Object nameIndex = pool[thisClassIndex];
        if (!(nameIndex instanceof Integer index) || !(pool[index] instanceof String name)) {
            throw new IllegalArgumentException("无法解析 classfile this_class。");
        }
        return name;
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashed = digest.digest(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte b : hashed) {
            builder.append(String.format(Locale.ROOT, "%02x", b));
        }
        return builder.toString();
    }

    private static String stackMessage(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(throwable.getClass().getName()).append(": ").append(String.valueOf(throwable.getMessage()));
        Throwable cause = throwable.getCause();
        int depth = 0;
        while (cause != null && cause != throwable && depth++ < 4) {
            builder.append("\ncaused by ").append(cause.getClass().getName()).append(": ").append(String.valueOf(cause.getMessage()));
            cause = cause.getCause();
        }
        return builder.toString();
    }

    private static String packageName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static boolean isJavaIdentifier(String value) {
        if (value == null || value.isBlank() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isJavaIdentifierPart(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String sanitize(String value) {
        String sanitized = value == null ? "class" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        sanitized = sanitized.replaceAll("^-+|-+$", "");
        return sanitized.isBlank() ? "class" : sanitized.substring(0, Math.min(sanitized.length(), 80));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, Math.max(0, max)) + "...";
    }

    public record ClassDefineResult(boolean success, boolean compileFailure, String className, String message,
                                    String diagnostics, Path sourcePath, Path diagnosticsPath, Path classPath,
                                    String runtimeStatus) {
    }

    public record CompileCheckResult(boolean success, boolean compileFailure, String className, String message,
                                     String diagnostics, Path sourcePath, Path diagnosticsPath) {
    }

    private record DefineInput(String className, byte[] bytes, String sha256, int major, int minor) {
    }

    private record DefineBatchOutcome(DefineInput primaryInput, DefineOutcome primaryOutcome,
                                      Map<String, DefineOutcome> outcomes) {
    }

    private enum DefineStatus {
        DEFINED("defined", "define 成功"),
        REDEFINED("redefined", "redefine 成功"),
        REDEFINED_ISOLATED("redefined_isolated", "新版本已用隔离 ClassLoader 加载"),
        SAVED_RESTART_REQUIRED("saved_restart_required", "已保存，需重启生效");

        private final String id;
        private final String text;

        DefineStatus(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    private record DefineOutcome(Class<?> runtimeClass, DefineStatus status, String detail) {
        private String runtimeStatus() {
            return status.id;
        }

        private String actionText() {
            return status.text;
        }
    }

    private record CompileResult(boolean success, String diagnostics) {
    }
}
