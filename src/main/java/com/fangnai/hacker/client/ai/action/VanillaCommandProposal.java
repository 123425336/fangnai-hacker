package com.fangnai.hacker.client.ai.action;

import com.fangnai.hacker.client.command.ChatFeedback;
import net.minecraft.client.Minecraft;

public final class VanillaCommandProposal extends ActionProposal {
    private final String command;

    public VanillaCommandProposal(String command, String reason, String expectedEffect) {
        super("command", reason, expectedEffect);
        this.command = normalize(command);
    }

    public String command() {
        return command;
    }

    @Override
    public String summary() {
        return "执行命令 /" + command + "；原因：" + reason() + "；效果：" + expectedEffect();
    }

    @Override
    public void execute() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            ChatFeedback.error("无法执行命令：玩家或连接不存在。");
            return;
        }
        if (command.isBlank()) {
            ChatFeedback.error("无法执行空命令。");
            return;
        }
        minecraft.getConnection().sendCommand(command);
        ChatFeedback.info("已按正常玩家权限发送命令：/" + command);
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
