package com.fangnai.hacker.client.ai.api;

import com.google.gson.JsonObject;

public final class AiToolCall {
    private final String id;
    private final String name;
    private final JsonObject arguments;

    public AiToolCall(String id, String name, JsonObject arguments) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.arguments = arguments == null ? new JsonObject() : arguments;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public JsonObject arguments() {
        return arguments;
    }
}
