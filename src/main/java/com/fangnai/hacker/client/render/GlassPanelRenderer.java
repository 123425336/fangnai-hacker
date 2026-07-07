package com.fangnai.hacker.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class GlassPanelRenderer {
    private static final int GLASS_TOP = 0x24FFFFFF;
    private static final int GLASS_BOTTOM = 0x0EFFFFFF;
    private static final int GLASS_SHEEN = 0x3CFFFFFF;
    private static final int BORDER = 0x66FFFFFF;
    private static final int BORDER_DIM = 0x28DDFBFF;

    private GlassPanelRenderer() {
    }

    public static void renderPanel(GuiGraphics graphics, Minecraft minecraft, int x, int y, int width, int height, int radius) {
        renderPanel(graphics, minecraft, x, y, width, height, radius, 7.0F);
    }

    public static void renderPanel(GuiGraphics graphics, Minecraft minecraft, int x, int y, int width, int height, int radius, float blurRadius) {
        HudBlurRenderer.renderBlurredRegion(minecraft, x, y, width, height, blurRadius);
        drawRoundedGradient(graphics, x, y, width, height, radius, GLASS_TOP, GLASS_BOTTOM);
        graphics.fill(x + radius + 2, y + 2, x + width - radius - 2, y + 3, GLASS_SHEEN);
        graphics.fill(x + radius + 5, y + 4, x + width - radius - 5, y + 5, 0x22FFFFFF);
        drawRoundedBorder(graphics, x, y, width, height, radius, BORDER, BORDER_DIM);
    }

    private static void drawRoundedGradient(GuiGraphics graphics, int x, int y, int width, int height, int radius, int topColor, int bottomColor) {
        graphics.fillGradient(x + radius, y, x + width - radius, y + height, topColor, bottomColor);
        graphics.fillGradient(x, y + radius, x + width, y + height - radius, topColor, bottomColor);
        graphics.fillGradient(x + 2, y + 2, x + radius, y + radius, topColor, 0x08FFFFFF);
        graphics.fillGradient(x + width - radius, y + 2, x + width - 2, y + radius, topColor, 0x08FFFFFF);
        graphics.fillGradient(x + 2, y + height - radius, x + radius, y + height - 2, bottomColor, 0x06FFFFFF);
        graphics.fillGradient(x + width - radius, y + height - radius, x + width - 2, y + height - 2, bottomColor, 0x06FFFFFF);
    }

    private static void drawRoundedBorder(GuiGraphics graphics, int x, int y, int width, int height, int radius, int bright, int dim) {
        graphics.fill(x + radius, y, x + width - radius, y + 1, bright);
        graphics.fill(x + radius, y + height - 1, x + width - radius, y + height, dim);
        graphics.fill(x, y + radius, x + 1, y + height - radius, dim);
        graphics.fill(x + width - 1, y + radius, x + width, y + height - radius, dim);
        graphics.fill(x + 2, y + 2, x + radius, y + 3, bright);
        graphics.fill(x + width - radius, y + 2, x + width - 2, y + 3, bright);
        graphics.fill(x + 2, y + height - 3, x + radius, y + height - 2, dim);
        graphics.fill(x + width - radius, y + height - 3, x + width - 2, y + height - 2, dim);
    }
}
