package com.fangnai.hacker.client.ai.action;

import com.fangnai.hacker.client.ai.session.AiConversationManager;
import com.fangnai.hacker.client.ai.tool.ToolRegistry;

public final class SavedToolProposal extends ActionProposal {
    private final String toolId;
    private final String input;

    public SavedToolProposal(String toolId, String input, String reason, String expectedEffect) {
        super("saved_tool", reason, expectedEffect);
        this.toolId = blankToDefault(toolId, "");
        this.input = input == null ? "" : input.trim();
    }

    public String toolId() {
        return toolId;
    }

    public String input() {
        return input;
    }

    @Override
    public String summary() {
        return "使用已保存工具 " + toolId
                + (input.isBlank() ? "；input=未提供，将尝试从原因/工具方法中安全推断" : "；input=" + input)
                + "；原因：" + reason()
                + "；效果：" + expectedEffect();
    }

    @Override
    public void execute() {
        ToolRegistry.SavedToolExecutionResult result = ToolRegistry.get()
                .executeSavedTool(toolId, input, reason(), expectedEffect());
        if (result == null) {
            return;
        }
        if (result.success()) {
            AiConversationManager.get().clearSavedToolRecovery();
            AiConversationManager.get().rememberAssistantEvent("saved tool execution succeeded: "
                    + result.toolId() + "." + result.invokedMethod() + "() status=" + result.status());
            return;
        }
        if (result.recoverable()) {
            AiConversationManager.get().requestSavedToolFailureRecovery(this, result);
        }
    }
}
