package com.fangnai.hacker.client.ai.action;

import com.fangnai.hacker.client.ai.session.AiConversationManager;
import com.fangnai.hacker.client.command.ChatFeedback;
import com.fangnai.hacker.client.config.HackerClientConfig;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PendingActionRegistry {
    private static final PendingActionRegistry INSTANCE = new PendingActionRegistry();

    private final Map<String, ActionProposal> actions = new LinkedHashMap<>();
    private int batchAddCount;

    private PendingActionRegistry() {
    }

    public static PendingActionRegistry get() {
        return INSTANCE;
    }

    public synchronized void add(ActionProposal proposal) {
        expireOldActions();
        while (actions.size() >= HackerClientConfig.ai().maxPendingActions) {
            Iterator<String> iterator = actions.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            } else {
                break;
            }
        }
        actions.put(proposal.id(), proposal);
        ChatFeedback.pending(proposal.id(), proposal.summary());
    }

    /** 批量添加一组操作，之后统一显示一行"全部确认 / 全部拒绝"摘要按钮。 */
    public void addBatch(java.util.List<ActionProposal> proposals) {
        if (proposals == null || proposals.isEmpty()) {
            return;
        }
        synchronized (this) {
            for (ActionProposal proposal : proposals) {
                expireOldActions();
                while (actions.size() >= HackerClientConfig.ai().maxPendingActions) {
                    Iterator<String> iterator = actions.keySet().iterator();
                    if (iterator.hasNext()) {
                        iterator.next();
                        iterator.remove();
                    } else {
                        break;
                    }
                }
                actions.put(proposal.id(), proposal);
                ChatFeedback.pending(proposal.id(), proposal.summary());
            }
            batchAddCount++;
        }
        ChatFeedback.pendingBatchSummary(proposals.size(), batchAddCount);
    }

    public void confirm(String id) {
        ActionProposal proposal;
        synchronized (this) {
            proposal = actions.remove(id);
            if (proposal == null) {
                ChatFeedback.warn("未找到待确认操作：" + id);
                return;
            }
            if (proposal.isExpired()) {
                ChatFeedback.warn("待确认操作已过期：" + id);
                return;
            }
        }
        proposal.execute();
        AiConversationManager.get().rememberAssistantEvent("user confirmed action " + proposal.type()
                + " (" + id + "): " + proposal.summary());
    }

    public void confirmAll() {
        java.util.List<ActionProposal> toConfirm;
        synchronized (this) {
            expireOldActions();
            toConfirm = new java.util.ArrayList<>(actions.values());
            actions.clear();
        }
        if (toConfirm.isEmpty()) {
            ChatFeedback.info("没有待确认操作。");
            return;
        }
        ChatFeedback.info("正在执行全部 " + toConfirm.size() + " 个待确认操作…");
        int ok = 0;
        int fail = 0;
        for (ActionProposal proposal : toConfirm) {
            try {
                proposal.execute();
                AiConversationManager.get().rememberAssistantEvent("user confirmed-all action " + proposal.type()
                        + " (" + proposal.id() + "): " + proposal.summary());
                ok++;
            } catch (Throwable t) {
                ChatFeedback.error("执行操作失败 [" + proposal.id() + "]：" + t.getMessage());
                fail++;
            }
        }
        ChatFeedback.info("全部确认完成：成功 " + ok + "，失败 " + fail + "。");
    }

    public synchronized void denyAll() {
        expireOldActions();
        int count = actions.size();
        if (count == 0) {
            ChatFeedback.info("没有待确认操作。");
            return;
        }
        actions.clear();
        ChatFeedback.info("已拒绝全部 " + count + " 个待确认操作。");
        AiConversationManager.get().rememberAssistantEvent("user denied-all " + count + " pending actions.");
    }

    public synchronized void deny(String id) {
        ActionProposal proposal = actions.remove(id);
        if (proposal == null) {
            ChatFeedback.warn("未找到待确认操作：" + id);
            return;
        }
        ChatFeedback.info("已拒绝操作：" + proposal.summary());
        AiConversationManager.get().rememberAssistantEvent("user denied action " + proposal.type()
                + " (" + id + "): " + proposal.summary());
    }

    public synchronized void expireOldActions() {
        Iterator<Map.Entry<String, ActionProposal>> iterator = actions.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().isExpired()) {
                iterator.remove();
            }
        }
    }

    public synchronized int size() {
        expireOldActions();
        return actions.size();
    }
}
