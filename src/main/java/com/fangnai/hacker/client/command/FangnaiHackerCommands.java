package com.fangnai.hacker.client.command;

import com.fangnai.hacker.client.ai.action.EntityClearProposal;
import com.fangnai.hacker.client.ai.action.PendingActionRegistry;
import com.fangnai.hacker.client.ai.session.AiConversationManager;
import com.fangnai.hacker.client.ai.tool.ToolRegistry;
import com.fangnai.hacker.client.config.HackerClientConfig;
import com.fangnai.hacker.client.generated.FangnaiRemove;
import com.fangnai.hacker.client.instrument.InstrumentationAccess;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.Locale;

public final class FangnaiHackerCommands {
    private FangnaiHackerCommands() {
    }

    public static void register(CommandDispatcher<SharedSuggestionProvider> dispatcher) {
        if (dispatcher == null) {
            return;
        }
        CommandNode<SharedSuggestionProvider> root = dispatcher.getRoot().getChild("fangnai");
        if (root != null && root.getChild("hacker") != null) {
            return;
        }
        dispatcher.register(literal("fangnai")
                .then(literal("hacker")
                        .executes(context -> help())
                        .then(literal("ask")
                                .then(argument("prompt", StringArgumentType.greedyString())
                                        .executes(context -> ask(StringArgumentType.getString(context, "prompt")))))
                        .then(literal("status").executes(context -> status()))
                        .then(literal("reload").executes(context -> reload()))
                        .then(literal("cancel").executes(context -> cancel()))
                        .then(literal("memory")
                                .then(literal("status").executes(context -> memoryStatus()))
                                .then(literal("clear").executes(context -> memoryClear())))
                        .then(literal("confirm")
                                .then(argument("id", StringArgumentType.word())
                                        .executes(context -> confirm(StringArgumentType.getString(context, "id")))))
                        .then(literal("confirmall").executes(context -> confirmAll()))
                        .then(literal("deny")
                                .then(argument("id", StringArgumentType.word())
                                        .executes(context -> deny(StringArgumentType.getString(context, "id")))))
                        .then(literal("denyall").executes(context -> denyAll()))
                        .then(literal("tools")
                                .then(literal("list").executes(context -> toolsList()))
                                .then(literal("info")
                                        .then(argument("toolId", StringArgumentType.word())
                                                .executes(context -> toolInfo(StringArgumentType.getString(context, "toolId"))))))
                        .then(argument("prompt", StringArgumentType.greedyString())
                                .executes(context -> ask(StringArgumentType.getString(context, "prompt"))))));
    }

    private static LiteralArgumentBuilder<SharedSuggestionProvider> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static RequiredArgumentBuilder<SharedSuggestionProvider, String> argument(String name, StringArgumentType type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    public static boolean dispatch(String command) {
        if (command == null) {
            return false;
        }
        String trimmed = command.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }
        if (trimmed.isBlank()) {
            return false;
        }

        String[] root = trimmed.split("\\s+", 3);
        if (!"fangnai".equalsIgnoreCase(root[0])) {
            return false;
        }
        if (root.length < 2 || !"hacker".equalsIgnoreCase(root[1])) {
            help();
            return true;
        }
        if (root.length < 3 || root[2].isBlank()) {
            help();
            return true;
        }

        String rest = root[2].trim();
        String[] parts = rest.split("\\s+", 3);
        String action = parts[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "ask" -> {
                if (parts.length < 2 || parts[1].isBlank()) {
                    ChatFeedback.warn("用法：/fangnai hacker ask <自然语言>");
                } else {
                    ask(rest.substring(parts[0].length()).trim());
                }
                return true;
            }
            case "status" -> {
                status();
                return true;
            }
            case "reload" -> {
                reload();
                return true;
            }
            case "cancel" -> {
                cancel();
                return true;
            }
            case "memory" -> {
                dispatchMemory(parts.length >= 2 ? rest.substring(parts[0].length()).trim() : "");
                return true;
            }
            case "confirm" -> {
                if (parts.length < 2 || parts[1].isBlank()) {
                    ChatFeedback.warn("用法：/fangnai hacker confirm <id>");
                } else {
                    confirm(parts[1].trim().split("\\s+")[0]);
                }
                return true;
            }
            case "confirmall" -> {
                confirmAll();
                return true;
            }
            case "deny" -> {
                if (parts.length < 2 || parts[1].isBlank()) {
                    ChatFeedback.warn("用法：/fangnai hacker deny <id>");
                } else {
                    deny(parts[1].trim().split("\\s+")[0]);
                }
                return true;
            }
            case "denyall" -> {
                denyAll();
                return true;
            }
            case "tools" -> {
                dispatchTools(parts.length >= 2 ? rest.substring(parts[0].length()).trim() : "");
                return true;
            }
            default -> {
                ask(rest);
                return true;
            }
        }
    }

    public static boolean runLocalClickCommand(String command) {
        return dispatch(command);
    }

    private static void dispatchMemory(String rest) {
        String action = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        if (action.isBlank() || "status".equals(action)) {
            memoryStatus();
            return;
        }
        if ("clear".equals(action)) {
            memoryClear();
            return;
        }
        ChatFeedback.warn("未知 memory 子命令：" + action + "。用法：/fangnai hacker memory status 或 memory clear");
    }

    private static void dispatchTools(String rest) {
        if (rest.isBlank()) {
            ChatFeedback.warn("用法：/fangnai hacker tools list，或 /fangnai hacker tools info <toolId>");
            return;
        }
        String[] parts = rest.trim().split("\\s+", 2);
        String action = parts[0].toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            toolsList();
            return;
        }
        if ("info".equals(action)) {
            if (parts.length < 2 || parts[1].isBlank()) {
                ChatFeedback.warn("用法：/fangnai hacker tools info <toolId>");
            } else {
                toolInfo(parts[1].trim().split("\\s+")[0]);
            }
            return;
        }
        ChatFeedback.warn("未知 tools 子命令：" + action);
    }

    private static int ask(String prompt) {
        EntityClearProposal clearProposal = EntityClearProposal.fromPrompt(prompt);
        if (clearProposal != null) {
            PendingActionRegistry.get().add(clearProposal);
            AiConversationManager.get().rememberAssistantEvent("local entity clear intent proposed: " + clearProposal.summary());
            return 1;
        }
        if (EntityClearProposal.needsAiTargetResolution(prompt)) {
            String catalog = FangnaiRemove.currentEntityCatalog();
            AiConversationManager.get().ask(EntityClearProposal.buildAiTargetResolutionPrompt(prompt, catalog));
            return 1;
        }
        AiConversationManager.get().ask(prompt);
        return 1;
    }

    private static int status() {
        HackerClientConfig.AiSettings ai = HackerClientConfig.ai();
        ChatFeedback.info("AI enabled=" + ai.enabled
                + ", endpoint=" + ai.endpointMode
                + ", baseUrl=" + ai.baseUrl
                + ", model=" + (ai.model.isBlank() ? "not set" : ai.model)
                + ", key=" + ai.maskedApiKey()
                + ", request=" + AiConversationManager.get().status()
                + ", pending=" + PendingActionRegistry.get().size()
                + ", " + InstrumentationAccess.statusSummary()
                + ", toolDir=" + ToolRegistry.get().root());
        return 1;
    }

    private static int reload() {
        HackerClientConfig.load();
        ToolRegistry.get().reload();
        ChatFeedback.info("已重载 AI 配置与工具索引。");
        return 1;
    }

    private static int cancel() {
        AiConversationManager.get().cancel();
        return 1;
    }

    private static int memoryStatus() {
        ChatFeedback.info(AiConversationManager.get().memoryStatus());
        return 1;
    }

    private static int memoryClear() {
        AiConversationManager.get().clearMemory();
        ChatFeedback.info("AI 上下文记忆已清空。");
        return 1;
    }

    private static int confirm(String id) {
        PendingActionRegistry.get().confirm(id);
        return 1;
    }

    private static int confirmAll() {
        PendingActionRegistry.get().confirmAll();
        return 1;
    }

    private static int deny(String id) {
        PendingActionRegistry.get().deny(id);
        return 1;
    }

    private static int denyAll() {
        PendingActionRegistry.get().denyAll();
        return 1;
    }

    private static int toolsList() {
        ToolRegistry.get().listToChat();
        return 1;
    }

    private static int toolInfo(String id) {
        ToolRegistry.get().showInfo(id);
        return 1;
    }

    private static int help() {
        ChatFeedback.info("用法：/fangnai hacker <自然语言>，或 status/reload/cancel/confirm/deny/confirmall/denyall/tools list/memory status/memory clear。示例：清除羊、全部清除、循环清除、停止循环清除。AI 生成多个待确认操作时会在底部显示\"全部确认\"按钮。");
        return 1;
    }
}
