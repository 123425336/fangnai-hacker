package com.fangnai.hacker.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

import static com.fangnai.hacker.HackerMod.MOD_ID;

@Mod.EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientShaders {
    private static ShaderInstance glassBlurShader;

    private ClientShaders() {
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), new ResourceLocation(MOD_ID, "glass_blur"), DefaultVertexFormat.POSITION_TEX_COLOR),
                shader -> glassBlurShader = shader
        );
    }

    public static ShaderInstance getGlassBlurShader() {
        return glassBlurShader;
    }
}
