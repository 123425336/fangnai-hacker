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

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class TransformService {
    private static final TransformService INSTANCE = new TransformService();
    private static final int MAX_TARGETS = 500;

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
        if (instrumentation == null || !instrumentation.isRetransformClassesSupported()) {
            ChatFeedback.error("动态 retransform 需要 agent attach 成功，且 JVM 支持 retransform。");
            return;
        }
        ReturnMode mode = ReturnMode.byName(returnMode);
        List<Class<?>> targets = resolveTargets(instrumentation, targetPattern);
        if (targets.isEmpty()) {
            ChatFeedback.warn("没有找到匹配的已加载类：" + targetPattern);
            return;
        }
        if (targets.size() > MAX_TARGETS) {
            ChatFeedback.warn("匹配类过多（" + targets.size() + "），请缩小目标。最多允许 " + MAX_TARGETS + " 个。");
            return;
        }
        DefaultReturnTransformer transformer = new DefaultReturnTransformer(targets, methodPattern, mode);
        try {
            instrumentation.addTransformer(transformer, true);
            instrumentation.retransformClasses(targets.toArray(Class[]::new));
            JsonObject recipe = new JsonObject();
            recipe.addProperty("operation", "default_return_transform");
            recipe.addProperty("targetPattern", targetPattern);
            recipe.addProperty("methodPattern", methodPattern == null ? "" : methodPattern);
            recipe.addProperty("returnMode", mode.configName);
            recipe.addProperty("reason", reason);
            recipe.addProperty("expectedEffect", expectedEffect);
            recipe.addProperty("transformedMethods", transformer.transformedMethods());
            JsonArray classes = new JsonArray();
            for (Class<?> target : targets) {
                classes.add(target.getName());
            }
            recipe.add("resolvedClasses", classes);
            ToolRegistry.get().recordRecipe("default-return " + targetPattern, recipe.toString(), riskLevel(targets));
            ChatFeedback.info("已 retransform " + targets.size() + " 个类。模式=" + mode.configName
                    + "，修改方法数=" + transformer.transformedMethods()
                    + "。void 直接 return，boolean 返回 false，int 返回 0；重启游戏可恢复原始字节码。");
        } catch (Throwable throwable) {
            ChatFeedback.error("retransform 失败：" + throwable.getMessage());
        } finally {
            try {
                instrumentation.removeTransformer(transformer);
            } catch (Throwable ignored) {
            }
        }
    }

    private List<Class<?>> resolveTargets(Instrumentation instrumentation, String targetPattern) {
        String pattern = targetPattern == null ? "" : targetPattern.trim();
        Pattern regex = tryRegex(pattern);
        String normalized = pattern.toLowerCase(Locale.ROOT).replace('/', '.');
        List<Class<?>> targets = new ArrayList<>();
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            if (loadedClass == null || loadedClass.isArray() || loadedClass.isPrimitive()) {
                continue;
            }
            if (!instrumentation.isModifiableClass(loadedClass)) {
                continue;
            }
            String name = loadedClass.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            boolean matches = !normalized.isBlank() && (lower.contains(normalized) || lower.startsWith(normalized));
            if (!matches && regex != null) {
                matches = regex.matcher(name).find();
            }
            if (matches) {
                targets.add(loadedClass);
            }
        }
        return targets;
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
        INT_ZERO("int_zero");

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
                default -> AUTO_DEFAULT;
            };
        }

        private boolean supports(String returnDescriptor) {
            return switch (this) {
                case AUTO_DEFAULT -> "V".equals(returnDescriptor) || "Z".equals(returnDescriptor) || "I".equals(returnDescriptor);
                case VOID_RETURN -> "V".equals(returnDescriptor);
                case BOOLEAN_FALSE -> "Z".equals(returnDescriptor);
                case INT_ZERO -> "I".equals(returnDescriptor);
            };
        }
    }

    private static final class DefaultReturnTransformer implements ClassFileTransformer {
        private final List<Class<?>> targets;
        private final String methodPattern;
        private final Pattern methodRegex;
        private final ReturnMode returnMode;
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

        @Override
        public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
            if (classBeingRedefined == null || !targets.contains(classBeingRedefined)) {
                return null;
            }
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
                            replacement.visitMaxs("V".equals(returnDescriptor) ? 0 : 1, 0);
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
                case "Z", "I" -> {
                    visitor.visitInsn(Opcodes.ICONST_0);
                    visitor.visitInsn(Opcodes.IRETURN);
                }
                default -> throw new IllegalArgumentException("unsupported return descriptor: " + returnDescriptor);
            }
        }
    }
}
