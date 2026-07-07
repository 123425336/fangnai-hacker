package com.fangnai.hacker.mixin;

import com.fangnai.hacker.client.instrument.InstrumentationAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onMinecraftConstructed(GameConfig gameConfig, CallbackInfo ci) {
        InstrumentationAccess.ensureAttachedAsync();
    }
}
