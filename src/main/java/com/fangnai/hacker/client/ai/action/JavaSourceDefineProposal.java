package com.fangnai.hacker.client.ai.action;

import com.fangnai.hacker.client.ai.session.AiConversationManager;
import com.fangnai.hacker.client.define.ClassDefineService;
import com.fangnai.hacker.client.config.HackerClientConfig;

public final class JavaSourceDefineProposal extends ActionProposal {
    private final String className;
    private final String javaSource;
    private final String sourceSha256;
    /** define 成功后自动执行的 public static 无参方法；为空时按原因/预期效果和方法列表推断。 */
    private final String runMethod;

    public JavaSourceDefineProposal(String className, String javaSource, String sourceSha256, String reason, String expectedEffect) {
        this(className, javaSource, sourceSha256, "", reason, expectedEffect);
    }

    public JavaSourceDefineProposal(String className, String javaSource, String sourceSha256, String runMethod,
                                    String reason, String expectedEffect) {
        super("compile_define_java_class", reason, expectedEffect);
        this.className = blankToDefault(className, "");
        this.javaSource = javaSource == null ? "" : javaSource;
        this.sourceSha256 = sourceSha256 == null ? "" : sourceSha256.trim().toLowerCase(java.util.Locale.ROOT);
        String method = runMethod == null ? "" : runMethod.trim();
        // 只接受合法的方法名，避免把整段说明文字当成方法名
        this.runMethod = method.matches("[A-Za-z_$][A-Za-z0-9_$]*") ? method : "";
    }

    public String className() {
        return className;
    }

    public String javaSource() {
        return javaSource;
    }

    public String sourceSha256() {
        return sourceSha256;
    }

    public String runMethod() {
        return runMethod;
    }

    @Override
    public String summary() {
        HackerClientConfig.AiSettings ai = HackerClientConfig.ai();
        String hash = sourceSha256.isBlank() ? "未提供" : sourceSha256;
        StringBuilder builder = new StringBuilder("编译并 define/redefine Java class " + className
                + "；source长度=" + javaSource.length()
                + "；sourceSha256=" + hash
                + "；同名 class 会覆盖已保存工具的 source/class/recipe/doc"
                + "；会先保存源码、执行 javac 语法/编译验证，再校验 class bytes"
                + "；未加载则 define，已加载则尝试 JVM redefine"
                + "；live redefine 需要 agent 且不能新增/删除字段方法或修改签名，结构变化会保存但通常需重启生效"
                + "；timeout=" + ai.compileDefineTimeoutSeconds + "s");
        if (ai.autoRunAfterDefine) {
            builder.append("；确认后 define 成功会自动执行").append(runMethod.isBlank() ? "工具" : " " + runMethod + "()")
                    .append("，没达成目标时香草会根据结果自动修复并重新执行");
            if (ai.autoRunMaxRounds > 0) {
                builder.append("（最多 ").append(ai.autoRunMaxRounds).append(" 轮）");
            }
        }
        builder.append("；原因：").append(reason())
                .append("；效果：").append(expectedEffect());
        return builder.toString();
    }

    @Override
    public void execute() {
        if (HackerClientConfig.ai().autoRunAfterDefine) {
            // 玩家确认一次后进入自动流程：define → 自动执行 → AI 判断 → 未达成就修复后再执行
            AiConversationManager.get().startAutoRunWorkflow(this);
            return;
        }
        ClassDefineService.ClassDefineResult result = ClassDefineService.get()
                .compileAndDefineResult(className, javaSource, sourceSha256, reason(), expectedEffect());
        ClassDefineService.get().reportCompileAndDefineResult(result);
        if (result != null && !result.success() && result.compileFailure()) {
            AiConversationManager.get().requestCompileRepair(this, result);
        }
    }
}
