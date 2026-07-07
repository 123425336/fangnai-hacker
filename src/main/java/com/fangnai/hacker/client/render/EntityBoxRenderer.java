package com.fangnai.hacker.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class EntityBoxRenderer {
    private static final float NORMAL_RED = 0.35F;
    private static final float NORMAL_GREEN = 0.95F;
    private static final float NORMAL_BLUE = 1.0F;
    private static final float NORMAL_ALPHA = 0.72F;

    private static final float TARGET_RED = 1.0F;
    private static final float TARGET_GREEN = 0.95F;
    private static final float TARGET_BLUE = 0.35F;
    private static final float TARGET_ALPHA = 1.0F;

    private EntityBoxRenderer() {
    }

    public static void render(PoseStack poseStack, Camera camera, Entity targetedEntity) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        Vec3 cameraPosition = camera.getPosition();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        VertexConsumer lineConsumer = bufferSource.getBuffer(SeeThroughLinesRenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-cameraPosition.x, -cameraPosition.y, -cameraPosition.z);

        try {
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (!shouldRender(entity)) {
                    continue;
                }

                boolean targeted = entity == targetedEntity;
                float red = targeted ? TARGET_RED : NORMAL_RED;
                float green = targeted ? TARGET_GREEN : NORMAL_GREEN;
                float blue = targeted ? TARGET_BLUE : NORMAL_BLUE;
                float alpha = targeted ? TARGET_ALPHA : NORMAL_ALPHA;

                AABB box = entity.getBoundingBox().inflate(0.015D);
                LevelRenderer.renderLineBox(poseStack, lineConsumer, box, red, green, blue, alpha);
                renderCenterLine(poseStack, lineConsumer, box, red, green, blue, Math.min(1.0F, alpha + 0.15F));
            }
        } finally {
            bufferSource.endBatch(SeeThroughLinesRenderType.lines());
            poseStack.popPose();
        }
    }

    private static boolean shouldRender(Entity entity) {
        if (entity == null || entity.isRemoved() || !entity.isAlive()) {
            return false;
        }

        return entity != Minecraft.getInstance().player;
    }

    private static void renderCenterLine(PoseStack poseStack, VertexConsumer consumer, AABB box,
                                         float red, float green, float blue, float alpha) {
        double centerX = (box.minX + box.maxX) * 0.5D;
        double centerZ = (box.minZ + box.maxZ) * 0.5D;
        Matrix4f pose = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();

        consumer.vertex(pose, (float) centerX, (float) box.maxY, (float) centerZ)
                .color(red, green, blue, alpha)
                .normal(normal, 0.0F, -1.0F, 0.0F)
                .endVertex();
        consumer.vertex(pose, (float) centerX, (float) box.minY, (float) centerZ)
                .color(red, green, blue, alpha)
                .normal(normal, 0.0F, -1.0F, 0.0F)
                .endVertex();
    }
}
