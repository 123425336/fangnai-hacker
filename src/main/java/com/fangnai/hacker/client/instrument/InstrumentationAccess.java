package com.fangnai.hacker.client.instrument;

import com.fangnai.hacker.client.command.ChatFeedback;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InstrumentationAccess {
    private static final String BRIDGE_CLASS = "com.fangnai.hacker.agent.InstrumentationBridge";
    private static final AtomicBoolean ATTACHING = new AtomicBoolean(false);
    private static volatile String lastAttachError = "";

    private InstrumentationAccess() {
    }

    public static void ensureAttachedAsync() {
        if (isAvailable() || !ATTACHING.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                attachCurrentVm();
                Thread.sleep(250L);
                if (!isAvailable()) {
                    throw new IllegalStateException("agentmain completed but instrumentation bridge is still unavailable");
                }
                lastAttachError = "";
            } catch (Throwable throwable) {
                lastAttachError = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
                ChatFeedback.warn("VirtualMachine attach agent 失败：" + lastAttachError);
            } finally {
                ATTACHING.set(false);
            }
        });
    }

    public static Instrumentation getInstrumentation() {
        try {
            Class<?> bridge = ClassLoader.getSystemClassLoader().loadClass(BRIDGE_CLASS);
            Method method = bridge.getMethod("getInstrumentation");
            Object value = method.invoke(null);
            return value instanceof Instrumentation instrumentation ? instrumentation : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isAvailable() {
        return getInstrumentation() != null;
    }

    public static boolean canRetransform() {
        Instrumentation instrumentation = getInstrumentation();
        return instrumentation != null && instrumentation.isRetransformClassesSupported();
    }

    public static boolean canRedefine() {
        Instrumentation instrumentation = getInstrumentation();
        return instrumentation != null && instrumentation.isRedefineClassesSupported();
    }

    public static String statusSummary() {
        Instrumentation instrumentation = getInstrumentation();
        if (instrumentation == null) {
            String suffix = lastAttachError.isBlank() ? "" : ", lastAttachError=" + lastAttachError;
            return "agent unavailable" + (ATTACHING.get() ? ", attaching" : suffix);
        }
        return "agent available, canRetransform=" + instrumentation.isRetransformClassesSupported()
                + ", canRedefine=" + instrumentation.isRedefineClassesSupported();
    }

    private static void attachCurrentVm() throws Exception {
        String pid = currentPid();
        Path agentJar = resolveAgentJar();
        if (!Files.isRegularFile(agentJar)) {
            throw new IllegalStateException("agent jar not found: " + agentJar);
        }

        Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
        Object vm = null;
        try {
            Method attach = vmClass.getMethod("attach", String.class);
            vm = attach.invoke(null, pid);
            Method loadAgent = vmClass.getMethod("loadAgent", String.class, String.class);
            loadAgent.invoke(vm, agentJar.toAbsolutePath().toString(), "external-attach");
        } catch (Throwable throwable) {
            runExternalAttachProcess(pid, agentJar);
        } finally {
            if (vm != null) {
                Method detach = vmClass.getMethod("detach");
                detach.invoke(vm);
            }
        }
    }

    private static void runExternalAttachProcess(String pid, Path agentJar) throws Exception {
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(javaBin.toString());
        command.add("--add-modules");
        command.add("jdk.attach");
        command.add("-cp");
        command.add(agentJar.toAbsolutePath().toString());
        command.add("com.fangnai.hacker.agent.ExternalAttachHelper");
        command.add(pid);
        command.add(agentJar.toAbsolutePath().toString());

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("external attach exited " + exit + (output.isBlank() ? "" : ": " + output));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String currentPid() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        int at = runtimeName.indexOf('@');
        return at > 0 ? runtimeName.substring(0, at) : runtimeName;
    }

    private static Path resolveAgentJar() throws Exception {
        try (InputStream input = InstrumentationAccess.class.getResourceAsStream("/assets/hacker/agent/hacker-agent.jar")) {
            if (input == null) {
                throw new IllegalStateException("bundled agent resource not found: /assets/hacker/agent/hacker-agent.jar");
            }
            Path temp = Files.createTempFile("fangnai-hacker-agent-", ".jar");
            Files.copy(input, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            temp.toFile().deleteOnExit();
            return temp;
        }
    }
}
