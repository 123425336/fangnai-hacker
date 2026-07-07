package com.fangnai.hacker.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Locale;

public final class GlassHudRenderer {
    private static final int MAX_PANEL_WIDTH = 210;
    private static final int MIN_PANEL_WIDTH = 132;
    private static final int PANEL_HEIGHT = 34;
    private static final int PANEL_TOP = 8;
    private static final int PANEL_PADDING_X = 10;

    private static final int TEXT_PRIMARY = 0xFFF7FDFF;
    private static final int TEXT_SECONDARY = 0xFFD7F8FF;

    private GlassHudRenderer() {
    }

    public static void render(GuiGraphics graphics, Minecraft minecraft, Entity entity) {
        if (minecraft.player == null) {
            return;
        }

        Font font = minecraft.font;
        String name = entity.getDisplayName().getString();
        String detail = formatHealth(entity) + "  •  " + String.format(Locale.ROOT, "%.1fm", minecraft.player.distanceTo(entity));

        int contentWidth = Math.max(font.width(name), font.width(detail));
        int panelWidth = Math.min(MAX_PANEL_WIDTH, Math.max(MIN_PANEL_WIDTH, contentWidth + PANEL_PADDING_X * 2));
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int x = (screenWidth - panelWidth) / 2;
        int y = PANEL_TOP;

        GlassPanelRenderer.renderPanel(graphics, minecraft, x, y, panelWidth, PANEL_HEIGHT, 5);

        int textX = x + PANEL_PADDING_X;
        graphics.drawString(font, trimToWidth(font, name, panelWidth - PANEL_PADDING_X * 2), textX, y + 5, TEXT_PRIMARY, true);
        graphics.drawString(font, trimToWidth(font, detail, panelWidth - PANEL_PADDING_X * 2), textX, y + 18, TEXT_SECONDARY, true);
    }

    private static String formatHealth(Entity entity) {
        if (entity instanceof LivingEntity living) {
            return String.format(Locale.ROOT, "❤ %.1f/%.1f", living.getHealth(), living.getMaxHealth());
        }

        return "❤ N/A";
    }

    private static String trimToWidth(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        int ellipsisWidth = font.width(ellipsis);
        StringBuilder builder = new StringBuilder(text);
        while (!builder.isEmpty() && font.width(builder.toString()) + ellipsisWidth > maxWidth) {
            builder.deleteCharAt(builder.length() - 1);
        }
        return builder + ellipsis;
    }
}
