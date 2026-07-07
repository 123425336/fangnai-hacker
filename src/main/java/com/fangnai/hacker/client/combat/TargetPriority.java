package com.fangnai.hacker.client.combat;

import net.minecraft.network.chat.Component;

public enum TargetPriority {
    NEAREST("screen.hacker.killarea.priority.nearest"),
    LOWEST_HEALTH("screen.hacker.killarea.priority.lowest_health"),
    CROSSHAIR("screen.hacker.killarea.priority.crosshair");

    private final String translationKey;

    TargetPriority(String translationKey) {
        this.translationKey = translationKey;
    }

    public Component displayName() {
        return Component.translatable(translationKey);
    }

    public TargetPriority next() {
        TargetPriority[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
