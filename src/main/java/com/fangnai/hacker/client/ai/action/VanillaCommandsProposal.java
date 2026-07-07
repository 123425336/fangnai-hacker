package com.fangnai.hacker.client.ai.action;

import com.fangnai.hacker.client.command.ChatFeedback;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class VanillaCommandsProposal extends ActionProposal {
    private final List<String> commands;

    public VanillaCommandsProposal(List<String> commands, String reason, String expectedEffect) {
        super("commands", reason, expectedEffect);
        List<String> normalized = new ArrayList<>();
        if (commands != null) {
            for (String command : commands) {
                String value = normalize(command);
                if (!value.isBlank()) {
                    normalized.add(value);
                }
            }
        }
        this.commands = Collections.unmodifiableList(normalized);
    }

    public int commandCount() {
        return commands.size();
    }

    public String firstCommand() {
        return commands.isEmpty() ? "" : commands.get(0);
    }

    @Override
    public String summary() {
        if (commands.isEmpty()) {
            return "执行 0 条命令；原因：" + reason() + "；效果：" + expectedEffect();
        }
        StringBuilder builder = new StringBuilder("批量执行 ").append(commands.size()).append(" 条命令：");
        int preview = Math.min(commands.size(), 4);
        for (int i = 0; i < preview; i++) {
            if (i > 0) {
                builder.append("；");
            }
            builder.append('/').append(commands.get(i));
        }
        if (commands.size() > preview) {
            builder.append("；...");
        }
        builder.append("；原因：").append(reason()).append("；效果：").append(expectedEffect());
        return builder.toString();
    }

    @Override
    public void execute() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            ChatFeedback.error("无法执行命令：玩家或连接不存在。");
            return;
        }
        if (commands.isEmpty()) {
            ChatFeedback.error("无法执行空命令列表。");
            return;
        }
        for (String command : commands) {
            minecraft.getConnection().sendCommand(command);
        }
        ChatFeedback.info("已按正常玩家权限批量发送 " + commands.size() + " 条命令。");
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }
}
