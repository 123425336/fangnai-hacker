package com.fangnai.hacker.client.input;

import com.fangnai.hacker.HackerMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = HackerMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModKeyMappings {
    public static final String CATEGORY = "key.categories.hacker";

    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.hacker.menu",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_RSHIFT,
            CATEGORY
    );

    public static final KeyMapping KILLAREA_TOGGLE = new KeyMapping(
            "key.hacker.killarea.toggle",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_K,
            CATEGORY
    );

    private ModKeyMappings() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_MENU);
        event.register(KILLAREA_TOGGLE);
    }
}
