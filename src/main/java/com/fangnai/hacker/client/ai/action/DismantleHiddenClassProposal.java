package com.fangnai.hacker.client.ai.action;

import com.fangnai.hacker.client.ai.session.AiConversationManager;
import com.fangnai.hacker.client.config.HackerClientConfig;
import com.fangnai.hacker.client.transform.TransformService;

public final class DismantleHiddenClassProposal extends ActionProposal {
    private final String targetPattern;

    public DismantleHiddenClassProposal(String targetPattern, String reason, String expectedEffect) {
        super("dismantle_hidden_class", reason, expectedEffect);
        this.targetPattern = blankToDefault(targetPattern, "");
    }

    public String targetPattern() {
        return targetPattern;
    }

    @Override
    public String summary() {
        StringBuilder builder = new StringBuilder("拆除隐藏类标志位：target[")
                .append(targetPattern)
                .append("]（仅永久清除 HotSpot 17 隐藏类标志位使 isModifiableClass=true，不改动方法字节码；排除 Lambda/动态代理等合成类）；原因：")
                .append(reason())
                .append("；效果：")
                .append(expectedEffect());
        if (HackerClientConfig.ai().jarAnalysisWorkflowEnabled) {
            builder.append("；确认后会自动把拆除结果交回香草继续完成后续分析或修改");
        }
        return builder.toString();
    }

    public TransformService.DismantleResult executeResult() {
        return TransformService.get().dismantleHiddenClasses(targetPattern, reason(), expectedEffect());
    }

    @Override
    public void execute() {
        if (HackerClientConfig.ai().jarAnalysisWorkflowEnabled) {
            AiConversationManager.get().startDismantleWorkflow(this);
            return;
        }
        executeResult();
    }
}
