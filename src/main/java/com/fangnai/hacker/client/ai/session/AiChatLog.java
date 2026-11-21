package com.fangnai.hacker.client.ai.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 给 AI 对话界面用的消息记录。
 * 所有聊天反馈都会同步写一份到这里，GUI 每帧读取快照来绘制对话气泡；
 * 只保存在内存中，退出游戏即清空。可能被网络线程写入，因此所有方法都加锁。
 */
public final class AiChatLog {
    /** 最多保留的消息条数，超出后丢弃最旧的。 */
    private static final int MAX_ENTRIES = 300;
    private static final AiChatLog INSTANCE = new AiChatLog();

    private final Deque<Entry> entries = new ArrayDeque<>();
    /** 每次内容变化自增，GUI 据此判断是否需要重新排版。 */
    private long version;

    private AiChatLog() {
    }

    public static AiChatLog get() {
        return INSTANCE;
    }

    public void user(String text) {
        add(new Entry(Kind.USER, text, "", System.currentTimeMillis()));
    }

    public void assistant(String text) {
        add(new Entry(Kind.ASSISTANT, text, "", System.currentTimeMillis()));
    }

    public void info(String text) {
        add(new Entry(Kind.INFO, text, "", System.currentTimeMillis()));
    }

    public void warn(String text) {
        add(new Entry(Kind.WARN, text, "", System.currentTimeMillis()));
    }

    public void error(String text) {
        add(new Entry(Kind.ERROR, text, "", System.currentTimeMillis()));
    }

    public void pending(String id, String summary) {
        add(new Entry(Kind.PENDING, summary, id == null ? "" : id, System.currentTimeMillis()));
    }

    public synchronized void clear() {
        entries.clear();
        version++;
    }

    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    public synchronized long version() {
        return version;
    }

    private synchronized void add(Entry entry) {
        if (entry.text() == null || entry.text().isBlank()) {
            return;
        }
        entries.addLast(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
        version++;
    }

    /** 消息类型：决定 GUI 中的气泡样式。 */
    public enum Kind {
        USER,
        ASSISTANT,
        INFO,
        WARN,
        ERROR,
        PENDING
    }

    /** 单条消息；pendingId 仅对待确认操作有效。 */
    public record Entry(Kind kind, String text, String pendingId, long timeMillis) {
    }
}
