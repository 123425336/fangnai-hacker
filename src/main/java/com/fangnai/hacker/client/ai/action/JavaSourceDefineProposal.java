package com.fangnai.hacker.client.ai.action;

import com.fangnai.hacker.client.ai.session.AiConversationManager;
import com.fangnai.hacker.client.define.ClassDefineService;
import com.fangnai.hacker.client.config.HackerClientConfig;

public final class JavaSourceDefineProposal extends ActionProposal {
    private final String className;
    private final String javaSource;
    private final String sourceSha256;

    public JavaSourceDefineProposal(String className, String javaSource, String sourceSha256, String reason, String expectedEffect) {
        super("compile_define_java_class", reason, expectedEffect);
        this.className = blankToDefault(className, "");
        this.javaSource = javaSource == null ? "" : javaSource;
        this.sourceSha256 = sourceSha256 == null ? "" : sourceSha256.trim().toLowerCase(java.util.Locale.ROOT);
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

    @Override
    public String summary() {
        HackerClientConfig.AiSettings ai = HackerClientConfig.ai();
        String hash = sourceSha256.isBlank() ? "未提供" : sourceSha256;
        return "编译并 define/redefine Java class " + className
                + "；source长度=" + javaSource.length()
                + "；sourceSha256=" + hash
                + "；同名 class 会覆盖已保存工具的 source/class/recipe/doc"
                + "；会先保存源码、执行 javac 语法/编译验证，再校验 class bytes"
                + "；未加载则 define，已加载则尝试 JVM redefine"
                + "；live redefine 需要 agent 且不能新增/删除字段方法或修改签名，结构变化会保存但通常需重启生效"
                + "；timeout=" + ai.compileDefineTimeoutSeconds + "s"
                + "；原因：" + reason()
                + "；效果：" + expectedEffect();
    }

    @Override
    public void execute() {
        ClassDefineService.ClassDefineResult result = ClassDefineService.get()
                .compileAndDefineResult(className, javaSource, sourceSha256, reason(), expectedEffect());
        ClassDefineService.get().reportCompileAndDefineResult(result);
        if (result != null && !result.success() && result.compileFailure()) {
            AiConversationManager.get().requestCompileRepair(this, result);
        }
    }
}
