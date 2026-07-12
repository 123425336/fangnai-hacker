package com.fangnai.hacker.client.ai.action;

import com.fangnai.hacker.client.transform.TransformService;

public final class TransformProposal extends ActionProposal {
    private final String targetPattern;
    private final String methodPattern;
    private final String returnMode;

    public TransformProposal(String targetPattern, String methodPattern, String reason, String expectedEffect) {
        this(targetPattern, methodPattern, "void_return", reason, expectedEffect);
    }

    public TransformProposal(String targetPattern, String methodPattern, String returnMode, String reason, String expectedEffect) {
        super("transform", reason, expectedEffect);
        this.targetPattern = blankToDefault(targetPattern, "");
        this.methodPattern = methodPattern == null ? "" : methodPattern.trim();
        this.returnMode = returnMode == null || returnMode.isBlank() ? "auto_default" : returnMode.trim();
    }

    @Override
    public String summary() {
        String method = methodPattern.isBlank() ? "匹配全部支持的方法" : "匹配方法 " + methodPattern;
        return "对目标[" + targetPattern + "]的" + method + "执行默认返回 transform；模式=" + returnMode
                + "（void return / 数值 0 / boolean false；auto_default 保留对象和数组返回，避免 null 破坏运行时契约）；原因："
                + reason() + "；效果：" + expectedEffect();
    }

    @Override
    public void execute() {
        TransformService.get().applyDefaultReturnTransform(targetPattern, methodPattern, returnMode, reason(), expectedEffect());
    }
}
