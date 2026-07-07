package com.fangnai.hacker.mixin;

import com.fangnai.hacker.client.combat.KillAreaModule;
import com.fangnai.hacker.client.command.FangnaiHackerCommands;
import com.fangnai.hacker.client.config.HackerClientConfig;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.telemetry.WorldSessionTelemetryManager;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
    @Shadow
    public CommandDispatcher<SharedSuggestionProvider> commands;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onConstructed(Minecraft minecraft, Screen screen, Connection connection, @Nullable ServerData serverData,
                               GameProfile gameProfile, WorldSessionTelemetryManager telemetryManager, CallbackInfo ci) {
        FangnaiHackerCommands.register(commands);
    }

    @Inject(method = "handleCommands", at = @At("RETURN"))
    private void onHandleCommands(ClientboundCommandsPacket packet, CallbackInfo ci) {
        FangnaiHackerCommands.register(commands);
    }

    @Inject(method = "handleSetEntityMotion", at = @At("HEAD"), cancellable = true)
    private void onSetEntityMotion(ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (packet.getId() != mc.player.getId()) return;
        if (KillAreaModule.isEnabled() && HackerClientConfig.killArea().noKnockback) {
            ci.cancel();
        }
    }

    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void onSendCommand(String command, CallbackInfo ci) {
        if (FangnaiHackerCommands.dispatch(command)) {
            ci.cancel();
        }
    }
}
