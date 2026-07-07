package com.fangnai.hacker.mixin;

import com.fangnai.hacker.client.command.FangnaiHackerCommands;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public class MixinScreen {
    @Inject(method = "handleComponentClicked", at = @At("HEAD"), cancellable = true)
    private void onHandleComponentClicked(Style style, CallbackInfoReturnable<Boolean> cir) {
        if (style == null || style.getClickEvent() == null) {
            return;
        }
        ClickEvent event = style.getClickEvent();
        if (event.getAction() != ClickEvent.Action.RUN_COMMAND) {
            return;
        }
        String value = event.getValue();
        if (value == null) {
            return;
        }
        String command = value.startsWith("/") ? value.substring(1) : value;
        if (FangnaiHackerCommands.runLocalClickCommand(command)) {
            cir.setReturnValue(true);
        }
    }
}
