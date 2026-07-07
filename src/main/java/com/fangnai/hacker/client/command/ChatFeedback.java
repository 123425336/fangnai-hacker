package com.fangnai.hacker.client.command;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;

public final class ChatFeedback {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PREFIX = "[Fangnai AI] ";

    private ChatFeedback() {
    }

    public static void info(String message) {
        LOGGER.info("{}{}", PREFIX, message);
        send(Component.literal(PREFIX).withStyle(ChatFormatting.AQUA).append(Component.literal(message).withStyle(ChatFormatting.WHITE)));
    }

    public static void warn(String message) {
        LOGGER.warn("{}{}", PREFIX, message);
        send(Component.literal(PREFIX).withStyle(ChatFormatting.YELLOW).append(Component.literal(message).withStyle(ChatFormatting.GOLD)));
    }

    public static void error(String message) {
        LOGGER.error("{}{}", PREFIX, message);
        send(Component.literal(PREFIX).withStyle(ChatFormatting.RED).append(Component.literal(message).withStyle(ChatFormatting.RED)));
    }

    public static void answer(String message) {
        LOGGER.info("{}AI answer: {}", PREFIX, truncate(message, 2000));
        send(Component.literal(PREFIX).withStyle(ChatFormatting.AQUA).append(Component.literal(message).withStyle(ChatFormatting.WHITE)));
    }

    public static void pending(String id, String summary) {
        LOGGER.warn("{}Pending action {}: {}", PREFIX, id, summary);
        MutableComponent line = Component.literal(PREFIX).withStyle(ChatFormatting.AQUA)
                .append(Component.literal("需要确认：").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(summary).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" "))
                .append(button("[确认]", "/fangnai hacker confirm " + id, ChatFormatting.GREEN))
                .append(Component.literal(" "))
                .append(button("[拒绝]", "/fangnai hacker deny " + id, ChatFormatting.RED));
        send(line);
    }

    public static void pendingBatchSummary(int count, int batchIndex) {
        LOGGER.info("{}Pending batch summary: {} actions", PREFIX, count);
        MutableComponent line = Component.literal(PREFIX).withStyle(ChatFormatting.AQUA)
                .append(Component.literal("共 " + count + " 个待确认操作  ").withStyle(ChatFormatting.YELLOW))
                .append(button("[ 全部确认 ]", "/fangnai hacker confirmall", ChatFormatting.GREEN))
                .append(Component.literal("  "))
                .append(button("[ 全部拒绝 ]", "/fangnai hacker denyall", ChatFormatting.RED));
        send(line);
    }

    private static MutableComponent button(String text, String command, ChatFormatting color) {
        return Component.literal(text).withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    public static void send(Component component) {
        Minecraft minecraft = Minecraft.getInstance();
        Runnable task = () -> {
            if (minecraft.gui != null) {
                minecraft.gui.getChat().addMessage(component);
            }
        };
        if (minecraft.isSameThread()) {
            task.run();
        } else {
            minecraft.execute(task);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
