package com.fangnai.hacker.client.render;

import com.fangnai.hacker.client.combat.KillAreaModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class KillAreaHudRenderer {
    private static final int TEXT_COLOR = 0xFFB0E8FF;

    private KillAreaHudRenderer() {
    }

    public static void render(GuiGraphics graphics, Minecraft minecraft) {
        if (!KillAreaModule.isEnabled() || minecraft.player == null) {
            return;
        }

        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        String text = "KillArea ON";
        int textWidth = minecraft.font.width(text);
        int x = screenWidth - textWidth - 8;
        int y = 8;

        graphics.drawString(minecraft.font, text, x, y, TEXT_COLOR, true);
    }
}
