package com.fangnai.hacker.client.target;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public final class EntityBoxPicker {
    private EntityBoxPicker() {
    }

    public static Entity pick(Minecraft minecraft, double maxDistance) {
        if (minecraft.level == null || minecraft.player == null) {
            return null;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 start = camera.getPosition();
        Vec3 direction = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot());
        Vec3 end = start.add(direction.scale(maxDistance));

        Entity closestEntity = null;
        double closestDistanceSqr = maxDistance * maxDistance;

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!canPick(minecraft, entity)) {
                continue;
            }

            AABB box = entity.getBoundingBox().inflate(0.08D);
            Optional<Vec3> hit = box.clip(start, end);
            if (hit.isEmpty()) {
                continue;
            }

            double distanceSqr = start.distanceToSqr(hit.get());
            if (distanceSqr < closestDistanceSqr) {
                closestDistanceSqr = distanceSqr;
                closestEntity = entity;
            }
        }

        return closestEntity;
    }

    private static boolean canPick(Minecraft minecraft, Entity entity) {
        if (entity == null || entity.isRemoved() || !entity.isAlive()) {
            return false;
        }

        return entity != minecraft.player;
    }
}
