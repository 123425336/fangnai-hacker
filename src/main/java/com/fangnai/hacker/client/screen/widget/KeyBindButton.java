package com.fangnai.hacker.client.screen.widget;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class KeyBindButton extends Button {
    private final KeyMapping keyMapping;
    private boolean listening;

    public KeyBindButton(int x, int y, int width, int height, KeyMapping keyMapping) {
        super(Button.builder(Component.empty(), button -> {
            if (button instanceof KeyBindButton keyButton) {
                keyButton.listening = true;
                keyButton.updateMessage();
            }
        }).bounds(x, y, width, height));
        this.keyMapping = keyMapping;
        updateMessage();
    }

    public boolean isListening() {
        return listening;
    }

    public void cancelListening() {
        listening = false;
        updateMessage();
    }

    public boolean setKey(int keyCode, int scanCode) {
        if (!listening) {
            return false;
        }

        InputConstants.Key key = keyCode == InputConstants.KEY_BACKSPACE || keyCode == InputConstants.KEY_DELETE
                ? InputConstants.UNKNOWN
                : InputConstants.getKey(keyCode, scanCode);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.options.setKey(keyMapping, key);
        KeyMapping.resetMapping();
        minecraft.options.save();
        listening = false;
        updateMessage();
        return true;
    }

    public void updateMessage() {
        Component keyName = keyMapping.getTranslatedKeyMessage();
        if (listening) {
            setMessage(Component.translatable("screen.hacker.key.waiting"));
        } else {
            setMessage(Component.translatable("screen.hacker.killarea.toggle_key", keyName));
        }
    }
}
