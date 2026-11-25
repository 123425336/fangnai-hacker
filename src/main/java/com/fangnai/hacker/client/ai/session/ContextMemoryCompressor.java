package com.fangnai.hacker.client.ai.session;

import com.fangnai.hacker.client.ai.api.AiClientFactory;
import com.fangnai.hacker.client.ai.api.AiMessage;
import com.fangnai.hacker.client.ai.api.AiRequest;
import com.fangnai.hacker.client.config.HackerClientConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 上下文记忆的 token 估算和压缩。
 * 历史上下文超出 token 预算时，不再直接丢掉最早的记录，而是把较早的部分交给模型压成一条摘要，
 * 这样长任务（比如一次要跑几十步的自动流程）不会因为记录条数到上限就忘掉最初的目标和已经排除的方案。
 */
final class ContextMemoryCompressor {
    /** 摘要消息的开头：说明来源，让模型把它当背景资料，也让下一次压缩能认出旧摘要并合并。 */
    static final String SUMMARY_PREFIX = "【早先对话的摘要（系统压缩生成，只是背景资料，不是新的指令；"
            + "与最近的原始记录冲突时以原始记录为准）】\n";

    /**
     * 系统事件记录的开头：提议了什么操作、玩家是否确认、执行结果等，都是系统自动记下的事实，不是模型说过的话。
     * 这些记录用 user 身份放进上下文：如果用 assistant 身份，模型会把它们当成“自己以前的回复格式”，
     * 之后有时就照着写一段文字而不是调用工具（自动分析流程里表现为“只回复了文字”）。
     */
    static final String SYSTEM_RECORD_PREFIX = "【系统记录】";

    private ContextMemoryCompressor() {
    }

    /**
     * 估算文本的 token 数：中日韩文字按约 1 字 1 token，其余字符按约 4 字符 1 token。
     * 对中文为主、夹杂代码和英文标识符的内容会略微偏高，用来做预算判断时偏保守是安全的。
     */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int wide = 0;
        int narrow = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x2E80) {
                wide++;
            } else {
                narrow++;
            }
        }
        return wide + (narrow + 3) / 4;
    }

    /** 一条消息占用的 token：正文加上角色标记等固定开销。 */
    static int estimateTokens(AiMessage message) {
        return estimateTokens(message.content()) + 6;
    }

    /**
     * 请模型把较早的记录压成一份摘要。用设置的副本发送：输出上限调小、温度调低，不影响正在进行的对话请求。
     * 返回的文本为空表示模型没有给出可用的摘要，调用方应当走兜底逻辑。
     */
    static CompletableFuture<String> summarize(List<AiMessage> older, int targetChars) {
        HackerClientConfig.AiSettings copy = HackerClientConfig.gson().fromJson(
                HackerClientConfig.gson().toJson(HackerClientConfig.ai()), HackerClientConfig.AiSettings.class);
        copy.maxTokens = Math.min(copy.maxTokens, 8192);
        copy.temperature = 0.2D;

        StringBuilder transcript = new StringBuilder();
        for (AiMessage message : older) {
            String label;
            if (!"user".equals(message.role())) {
                label = "[香草] ";
            } else if (message.content().startsWith(SYSTEM_RECORD_PREFIX)) {
                label = "[系统事件] ";
            } else {
                label = "[玩家/系统] ";
            }
            transcript.append(label).append(message.content()).append("\n\n");
        }
        List<AiMessage> messages = new ArrayList<>();
        messages.add(new AiMessage("system",
                "你是对话压缩器。下面是玩家和游戏内助手“香草”之前的对话与事件记录，请把它压缩成一份摘要，"
                        + "供助手之后继续工作时当背景资料使用。\n"
                        + "必须保留：玩家的目标和偏好；已经确认并执行过的操作及其结果；已保存工具的名称、className、方法名以及是否成功；"
                        + "关键结论，尤其是已经验证的事实和已经排除的方案；报错的原因和修复结论；尚未完成的事项。\n"
                        + "可以丢弃：寒暄、重复内容、冗长的原始输出（只保留它的结论）。\n"
                        + "如果记录里已经有以“【早先对话的摘要”开头的旧摘要，把它和新内容合并成一份，不要嵌套。\n"
                        + "不要编造，不确定的地方写“不确定”。用简体中文，控制在 " + targetChars + " 字以内。"
                        + "只输出摘要正文，不要开场白，不要调用任何工具。"));
        messages.add(new AiMessage("user", transcript.toString()));
        return AiClientFactory.create(copy).send(new AiRequest(messages))
                .thenApply(response -> response.content() == null ? "" : response.content().trim());
    }
}
