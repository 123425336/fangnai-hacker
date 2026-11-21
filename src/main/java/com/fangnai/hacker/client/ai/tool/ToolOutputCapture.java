package com.fangnai.hacker.client.ai.tool;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 自动执行工具时临时收集聊天栏新增的消息。
 * 工具可能把真正的操作放到服务端线程异步完成、再把结果打印到聊天栏，
 * 只看方法返回值会误判为成功，所以执行后一段时间内的聊天输出也要一并交给 AI 判断。
 */
public final class ToolOutputCapture {
    /** 本模组自己的提示消息带这个前缀，收集时跳过，避免把系统提示当成工具输出。 */
    private static final String OWN_PREFIX = "[Fangnai AI]";
    private static final int MAX_LINES = 200;
    private static final int MAX_LINE_CHARS = 2000;
    private static final List<ToolOutputCapture> ACTIVE = new CopyOnWriteArrayList<>();

    private final List<String> lines = new ArrayList<>();
    private boolean truncated;

    private ToolOutputCapture() {
    }

    /** 开始收集；必须在之后调用 {@link #stop()}，否则会一直收集下去。 */
    public static ToolOutputCapture start() {
        ToolOutputCapture capture = new ToolOutputCapture();
        ACTIVE.add(capture);
        return capture;
    }

    /** 由聊天栏 mixin 调用：每条新消息都分发给当前所有收集器。 */
    public static void onChatMessage(Component message) {
        if (ACTIVE.isEmpty() || message == null) {
            return;
        }
        String text = message.getString();
        if (text == null || text.isBlank() || text.startsWith(OWN_PREFIX)) {
            return;
        }
        for (ToolOutputCapture capture : ACTIVE) {
            capture.add(text);
        }
    }

    private synchronized void add(String text) {
        if (lines.size() >= MAX_LINES) {
            truncated = true;
            return;
        }
        lines.add(text.length() <= MAX_LINE_CHARS ? text : text.substring(0, MAX_LINE_CHARS) + "...");
    }

    /** 停止收集并返回期间收到的全部消息。 */
    public synchronized List<String> stop() {
        ACTIVE.remove(this);
        List<String> result = new ArrayList<>(lines);
        if (truncated) {
            result.add("...（输出过多，只保留前 " + MAX_LINES + " 行）");
        }
        return result;
    }
}
