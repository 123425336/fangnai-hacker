package com.fangnai.hacker.client.ai.api;

import java.util.Objects;

public final class AiMessage {
    private final String role;
    private final String content;

    public AiMessage(String role, String content) {
        this.role = Objects.requireNonNull(role, "role");
        this.content = content == null ? "" : content;
    }

    public String role() {
        return role;
    }

    public String content() {
        return content;
    }
}
