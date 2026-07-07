package com.fangnai.hacker.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;

public final class HudBlurRenderer {
    private static TextureTarget snapshotTarget;

    private HudBlurRenderer() {
    }

    public static void renderBlurredRegion(Minecraft minecraft, int x, int y, int width, int height, float radius) {
        Window window = minecraft.getWindow();
        int framebufferWidth = window.getWidth();
        int framebufferHeight = window.getHeight();
        if (framebufferWidth <= 0 || framebufferHeight <= 0) {
            return;
        }

        ensureTarget(framebufferWidth, framebufferHeight);

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        copyColor(mainTarget, snapshotTarget);

        int scissorX = scale(window, x);
        int scissorY = framebufferHeight - scale(window, y + height);
        int scissorWidth = Math.max(1, scale(window, width));
        int scissorHeight = Math.max(1, scale(window, height));

        mainTarget.bindWrite(false);
        RenderSystem.enableScissor(scissorX, scissorY, scissorWidth, scissorHeight);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        blitBlurred(snapshotTarget, framebufferWidth, framebufferHeight, radius);
        RenderSystem.disableScissor();
        RenderSystem.disableBlend();
        mainTarget.bindWrite(false);
    }

    private static void ensureTarget(int width, int height) {
        if (snapshotTarget == null) {
            snapshotTarget = new TextureTarget(width, height, false, Minecraft.ON_OSX);
            snapshotTarget.setFilterMode(9729);
            return;
        }

        if (snapshotTarget.width != width || snapshotTarget.height != height) {
            snapshotTarget.resize(width, height, Minecraft.ON_OSX);
            snapshotTarget.setFilterMode(9729);
        }
    }

    private static int scale(Window window, int guiPixels) {
        return (int) Math.round(guiPixels * window.getGuiScale());
    }

    private static void copyColor(RenderTarget source, RenderTarget target) {
        GlStateManager._glBindFramebuffer(36008, source.frameBufferId);
        GlStateManager._glBindFramebuffer(36009, target.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, source.width, source.height, 0, 0, target.width, target.height, 16384, 9729);
        GlStateManager._glBindFramebuffer(36160, 0);
    }

    private static void blitBlurred(RenderTarget source, int width, int height, float radius) {
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);

        ShaderInstance shader = ClientShaders.getGlassBlurShader();
        if (shader == null) {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        } else {
            Uniform texelSize = shader.getUniform("TexelSize");
            if (texelSize != null) {
                texelSize.set(1.0F / Math.max(1.0F, source.width), 1.0F / Math.max(1.0F, source.height));
            }
            Uniform blurRadius = shader.getUniform("BlurRadius");
            if (blurRadius != null) {
                blurRadius.set(radius);
            }
            RenderSystem.setShader(() -> shader);
        }

        RenderSystem.setShaderTexture(0, source.getColorTextureId());

        float uMax = (float) source.viewWidth / (float) source.width;
        float vMax = (float) source.viewHeight / (float) source.height;

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        builder.vertex(0.0D, (double) height, 0.0D).uv(0.0F, 0.0F).color(255, 255, 255, 190).endVertex();
        builder.vertex((double) width, (double) height, 0.0D).uv(uMax, 0.0F).color(255, 255, 255, 190).endVertex();
        builder.vertex((double) width, 0.0D, 0.0D).uv(uMax, vMax).color(255, 255, 255, 190).endVertex();
        builder.vertex(0.0D, 0.0D, 0.0D).uv(0.0F, vMax).color(255, 255, 255, 190).endVertex();
        BufferUploader.drawWithShader(builder.end());

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
