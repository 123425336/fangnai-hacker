package com.fangnai.hacker.client;

import com.fangnai.hacker.HackerMod;
import com.fangnai.hacker.client.ai.session.AiConversationManager;
import com.fangnai.hacker.client.combat.KillAreaModule;
import com.fangnai.hacker.client.generated.FangnaiRemove;
import com.fangnai.hacker.client.input.ModKeyMappings;
import com.fangnai.hacker.client.render.EntityBoxRenderer;
import com.fangnai.hacker.client.render.GlassHudRenderer;
import com.fangnai.hacker.client.render.KillAreaHudRenderer;
import com.fangnai.hacker.client.screen.FangnaiAiScreen;
import com.fangnai.hacker.client.screen.HackerSettingsScreen;
import com.fangnai.hacker.client.target.EntityBoxPicker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = HackerMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {
    private static final double PICK_DISTANCE = 128.0D;
    private static Entity targetedEntity;

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        while (ModKeyMappings.OPEN_MENU.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new HackerSettingsScreen());
            }
        }

        if (minecraft.screen != null) {
            return;
        }

        while (ModKeyMappings.KILLAREA_TOGGLE.consumeClick()) {
            KillAreaModule.toggle();
        }

        AiConversationManager.get().tick();
        FangnaiRemove.clientTick(minecraft);
        KillAreaModule.clientTick(minecraft);
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            targetedEntity = null;
            return;
        }

        targetedEntity = EntityBoxPicker.pick(minecraft, PICK_DISTANCE);
        EntityBoxRenderer.render(event.getPoseStack(), event.getCamera(), targetedEntity);
    }

    /** AI 对话界面打开时隐藏原版聊天栏：消息已经显示在界面里，避免文字透过半透明面板。 */
    @SubscribeEvent
    public static void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (Minecraft.getInstance().screen instanceof FangnaiAiScreen
                && VanillaGuiOverlay.CHAT_PANEL.id().equals(event.getOverlay().id())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderGuiOverlayPost(RenderGuiOverlayEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        if (targetedEntity != null && targetedEntity.isAlive()) {
            GlassHudRenderer.render(event.getGuiGraphics(), minecraft, targetedEntity);
        }
        KillAreaHudRenderer.render(event.getGuiGraphics(), minecraft);
    }
}
