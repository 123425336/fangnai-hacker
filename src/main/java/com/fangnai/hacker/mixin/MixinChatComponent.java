package com.fangnai.hacker.mixin;

import com.fangnai.hacker.client.ai.tool.ToolOutputCapture;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

@Mixin(ChatComponent.class)
public class MixinChatComponent {
    /** 所有新聊天消息最终都经过这个方法；自动执行工具期间把消息交给输出收集器。 */
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"))
    private void hacker$captureToolOutput(Component message, @Nullable MessageSignature signature,
                                          @Nullable GuiMessageTag tag, CallbackInfo ci) {
        ToolOutputCapture.onChatMessage(message);
    }
}
