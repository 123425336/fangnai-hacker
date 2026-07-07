package com.fangnai.hacker.client.combat;

import com.fangnai.hacker.client.config.HackerClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

public final class KillAreaTargetSelector {
    private KillAreaTargetSelector() {
    }

    public static LivingEntity select(Minecraft minecraft, HackerClientConfig.KillAreaSettings settings, LivingEntity currentTarget) {
        if (minecraft.level == null || minecraft.player == null) {
            return null;
        }

        settings.clamp();
        LocalPlayer player = minecraft.player;
        if (isValidTarget(player, currentTarget, settings)) {
            return currentTarget;
        }

        AABB searchBox = player.getBoundingBox().inflate(settings.reach);
        List<LivingEntity> candidates = minecraft.level.getEntitiesOfClass(
                LivingEntity.class,
                searchBox,
                entity -> isValidTarget(player, entity, settings)
        );

        return candidates.stream()
                .min(comparator(player, settings.priority))
                .orElse(null);
    }

    public static boolean isValidTarget(LocalPlayer player, LivingEntity entity, HackerClientConfig.KillAreaSettings settings) {
        if (entity == null || entity.isRemoved() || !entity.isAlive() || entity == player) {
            return false;
        }
        if (entity.getUUID().equals(player.getUUID())) {
            return false;
        }
        if (!entity.isAttackable() || entity.skipAttackInteraction(player)) {
            return false;
        }
        if (entity instanceof Player targetPlayer) {
            if (!settings.targetPlayers || targetPlayer.isSpectator()) {
                return false;
            }
            if (targetPlayer instanceof AbstractClientPlayer clientPlayer && clientPlayer.isCreative()) {
                return false;
            }
        } else if (entity instanceof Enemy || entity instanceof Monster) {
            if (!settings.targetHostiles) {
                return false;
            }
        } else if (entity instanceof Animal || entity instanceof WaterAnimal || entity instanceof AbstractVillager) {
            if (!settings.targetPassive) {
                return false;
            }
        } else if (!settings.targetNeutral) {
            return false;
        }

        if (player.isAlliedTo(entity)) {
            return false;
        }
        if (entity instanceof TamableAnimal tamable && tamable.isOwnedBy(player)) {
            return false;
        }
        if (settings.requireLineOfSight && !player.hasLineOfSight(entity)) {
            return false;
        }
        if (distanceToBoxSqr(player, entity) > settings.reach * settings.reach) {
            return false;
        }

        return isInsideFov(player, entity, settings.fovDegrees);
    }

    private static Comparator<LivingEntity> comparator(LocalPlayer player, TargetPriority priority) {
        if (priority == TargetPriority.LOWEST_HEALTH) {
            return Comparator
                    .comparingDouble((LivingEntity entity) -> entity.getHealth())
                    .thenComparingDouble(entity -> distanceToBoxSqr(player, entity));
        }
        if (priority == TargetPriority.CROSSHAIR) {
            return Comparator
                    .comparingDouble((LivingEntity entity) -> crosshairAngle(player, entity))
                    .thenComparingDouble(entity -> distanceToBoxSqr(player, entity));
        }

        return Comparator.comparingDouble((LivingEntity entity) -> distanceToBoxSqr(player, entity));
    }

    private static double distanceToBoxSqr(LocalPlayer player, Entity entity) {
        return entity.getBoundingBox().distanceToSqr(player.getEyePosition());
    }

    private static boolean isInsideFov(LocalPlayer player, LivingEntity entity, double fovDegrees) {
        if (fovDegrees >= 359.9D) {
            return true;
        }

        return crosshairAngle(player, entity) <= fovDegrees * 0.5D;
    }

    private static double crosshairAngle(LocalPlayer player, LivingEntity entity) {
        Vec3 eye = player.getEyePosition();
        Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(eye);
        if (toTarget.lengthSqr() <= 1.0E-6D) {
            return 0.0D;
        }

        Vec3 look = player.getLookAngle().normalize();
        Vec3 direction = toTarget.normalize();
        double dot = Math.max(-1.0D, Math.min(1.0D, look.dot(direction)));
        return Math.toDegrees(Math.acos(dot));
    }
}
