package com.fangnai.hacker.client.ai.action;

import com.fangnai.hacker.client.define.ClassDefineService;

public final class ClassDefineProposal extends ActionProposal {
    private final String className;
    private final String base64ClassBytes;
    private final String sha256;

    public ClassDefineProposal(String className, String base64ClassBytes, String sha256, String reason, String expectedEffect) {
        super("define_generated_class", reason, expectedEffect);
        this.className = blankToDefault(className, "");
        this.base64ClassBytes = base64ClassBytes == null ? "" : base64ClassBytes.trim();
        this.sha256 = sha256 == null ? "" : sha256.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public String summary() {
        int encodedLength = base64ClassBytes.length();
        String hash = sha256.isBlank() ? "未提供" : sha256;
        return "动态 define/redefine class " + className
                + "；base64长度=" + encodedLength
                + "；sha256=" + hash
                + "；同名 class 会覆盖已保存工具的 class/recipe/doc"
                + "；未加载则 define，已加载则尝试 JVM redefine"
                + "；live redefine 需要 agent 且不能新增/删除字段方法或修改签名，结构变化会保存但通常需重启生效"
                + "；原因：" + reason()
                + "；效果：" + expectedEffect();
    }

    @Override
    public void execute() {
        ClassDefineService.get().define(className, base64ClassBytes, sha256, reason(), expectedEffect());
    }
}
