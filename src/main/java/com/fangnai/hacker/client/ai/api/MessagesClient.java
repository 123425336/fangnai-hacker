package com.fangnai.hacker.client.ai.api;

import com.fangnai.hacker.client.config.HackerClientConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

public final class MessagesClient extends AbstractJsonAiClient {
    public MessagesClient(HttpClient httpClient, HackerClientConfig.AiSettings settings) {
        super(httpClient, settings);
    }

    @Override
    protected String endpointPath() {
        return "/messages";
    }

    @Override
    protected JsonObject buildBody(AiRequest request) {
        JsonObject body = baseBody();
        JsonArray messages = new JsonArray();
        String system = "";
        for (AiMessage message : request.messages()) {
            if ("system".equals(message.role())) {
                system = message.content();
            } else {
                JsonObject object = new JsonObject();
                object.addProperty("role", message.role());
                object.addProperty("content", message.content());
                messages.add(object);
            }
        }
        if (!system.isBlank()) {
            if (settings.promptCachingEnabled) {
                JsonArray systemArray = new JsonArray();
                JsonObject systemBlock = new JsonObject();
                systemBlock.addProperty("type", "text");
                systemBlock.addProperty("text", system);
                JsonObject cacheControl = new JsonObject();
                cacheControl.addProperty("type", "ephemeral");
                systemBlock.add("cache_control", cacheControl);
                systemArray.add(systemBlock);
                body.add("system", systemArray);
            } else {
                body.addProperty("system", system);
            }
        }
        body.add("messages", messages);
        body.add("tools", ToolSchemaJson.anthropicTools());
        return body;
    }

    @Override
    protected AiResponse parseResponse(String body) {
        JsonObject root = parseJsonObject(body);
        JsonArray content = arrayMember(root, "content");
        StringBuilder text = new StringBuilder();
        List<AiToolCall> toolCalls = new ArrayList<>();
        for (JsonElement element : content) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject part = element.getAsJsonObject();
            String type = stringMember(part, "type");
            if ("text".equals(type)) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(stringMember(part, "text"));
            } else if ("tool_use".equals(type)) {
                toolCalls.add(new AiToolCall(stringMember(part, "id"), stringMember(part, "name"), objectMember(part, "input")));
            }
        }
        return new AiResponse(text.toString(), toolCalls, body);
    }
}
