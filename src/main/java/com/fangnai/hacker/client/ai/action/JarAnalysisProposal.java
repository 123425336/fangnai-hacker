package com.fangnai.hacker.client.ai.action;

import com.fangnai.hacker.client.ai.session.AiConversationManager;
import com.fangnai.hacker.client.ai.tool.JarAnalysisService;
import com.fangnai.hacker.client.config.HackerClientConfig;

public final class JarAnalysisProposal extends ActionProposal {
    private final String mode;
    private final String jarSelector;
    private final String classPattern;
    private final String memberPattern;
    private final String searchText;

    public JarAnalysisProposal(String mode, String jarSelector, String classPattern, String memberPattern,
                               String searchText, String reason, String expectedEffect) {
        super("jar_analysis", reason, expectedEffect);
        this.mode = blankToDefault(mode, "list_jars").toLowerCase(java.util.Locale.ROOT);
        this.jarSelector = jarSelector == null ? "" : jarSelector.trim();
        this.classPattern = classPattern == null ? "" : classPattern.trim();
        this.memberPattern = memberPattern == null ? "" : memberPattern.trim();
        this.searchText = searchText == null ? "" : searchText.trim();
    }

    public String mode() {
        return mode;
    }

    public String jarSelector() {
        return jarSelector;
    }

    public String classPattern() {
        return classPattern;
    }

    public String memberPattern() {
        return memberPattern;
    }

    public String searchText() {
        return searchText;
    }

    @Override
    public String summary() {
        HackerClientConfig.AiSettings ai = HackerClientConfig.ai();
        StringBuilder builder = new StringBuilder("分析 mods jar：mode=").append(mode);
        if (!jarSelector.isBlank()) {
            builder.append("，jar=").append(jarSelector);
        }
        if (!classPattern.isBlank()) {
            builder.append("，class=").append(classPattern);
        }
        if (!searchText.isBlank()) {
            builder.append("，search=").append(searchText);
        }
        builder.append("；原因：").append(reason())
                .append("；效果：").append(expectedEffect())
                .append("；限制：仅允许 mods 目录 jar，timeout=").append(ai.jarAnalysisTimeoutSeconds)
                .append("s，output<=").append(ai.jarAnalysisMaxOutputChars).append(" chars");
        if (ai.jarAnalysisWorkflowEnabled) {
            builder.append("；确认后会在限制内自动继续分析/请求 AI/编译并 define 生成类")
                    .append("，analysisSteps<=").append(ai.jarAnalysisWorkflowMaxAnalysisSteps)
                    .append("，aiTurns<=").append(ai.jarAnalysisWorkflowMaxAiTurns)
                    .append("，defineAttempts<=").append(ai.jarAnalysisWorkflowMaxDefineAttempts);
        }
        return builder.toString();
    }

    @Override
    public void execute() {
        if (HackerClientConfig.ai().jarAnalysisWorkflowEnabled) {
            AiConversationManager.get().startJarAnalysisWorkflow(this);
            return;
        }
        JarAnalysisService.get().analyze(mode, jarSelector, classPattern, memberPattern, searchText, reason(), expectedEffect());
    }
}
