package com.fangnai.hacker.client.combat;

import com.fangnai.hacker.client.config.HackerClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffects;

public final class KillAreaModule {
    private static boolean enabled;
    private static LivingEntity currentTarget;
    private static double attackCharge;

    private KillAreaModule() {
    }

    public static void toggle() {
        setEnabled(!enabled);
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!enabled) {
            currentTarget = null;
            attackCharge = 0.0D;
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static LivingEntity getCurrentTarget() {
        return currentTarget;
    }

    public static void clientTick(Minecraft minecraft) {
        if (!enabled) {
            currentTarget = null;
            attackCharge = 0.0D;
            return;
        }

        if (!canRun(minecraft)) {
            currentTarget = null;
            attackCharge = 0.0D;
            return;
        }

        HackerClientConfig.KillAreaSettings settings = HackerClientConfig.killArea();
        LocalPlayer player = minecraft.player;
        currentTarget = KillAreaTargetSelector.select(minecraft, settings, currentTarget);
        if (currentTarget == null) {
            attackCharge = Math.min(1.0D, attackCharge + settings.cps / 20.0D);
            return;
        }

        attackCharge = Math.min(1.0D, attackCharge + settings.cps / 20.0D);

        boolean cooldownReady = !settings.respectAttackCooldown
                || player.getAttackStrengthScale(0.0F) >= 1.0F;
        boolean cpsReady = !settings.respectAttackCooldown && attackCharge >= 1.0D
                || settings.respectAttackCooldown;

        if (!cooldownReady || !cpsReady) {
            return;
        }

        if (settings.critHit && canCrit(player)) {
            // 发两个假位置包：先上移0.11再回原位，服务端判定为下落 → 暴击
            double x = player.getX(), y = player.getY(), z = player.getZ();
            minecraft.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y + 0.11D, z, false));
            minecraft.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y, z, false));
        }

        minecraft.gameMode.attack(player, currentTarget);
        player.swing(InteractionHand.MAIN_HAND);
        attackCharge = Math.max(0.0D, attackCharge - 1.0D);
    }

    private static boolean canCrit(LocalPlayer player) {
        return !player.onClimbable()
                && !player.isInWater()
                && !player.isPassenger()
                && !player.isSprinting()
                && !player.hasEffect(MobEffects.BLINDNESS);
    }

    private static boolean canRun(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || minecraft.gameMode == null) {
            return false;
        }
        if (minecraft.screen != null || !minecraft.isWindowActive()) {
            return false;
        }

        LocalPlayer player = minecraft.player;
        if (!player.isAlive() || player.isSpectator()) {
            return false;
        }

        HackerClientConfig.KillAreaSettings settings = HackerClientConfig.killArea();
        if (settings.pauseWhileUsingItem
                && player.isUsingItem()
                && player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            return false;
        }
        return true;
    }
}
