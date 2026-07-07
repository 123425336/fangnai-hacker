package com.fangnai.hacker.client.ai.api;

import com.fangnai.hacker.client.config.HackerClientConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpClient;
import java.util.List;

public final class OpenAiCompletionsClient extends AbstractJsonAiClient {
    public OpenAiCompletionsClient(HttpClient httpClient, HackerClientConfig.AiSettings settings) {
        super(httpClient, settings);
    }

    @Override
    protected String endpointPath() {
        return "/completions";
    }

    @Override
    protected JsonObject buildBody(AiRequest request) {
        JsonObject body = baseBody();
        StringBuilder prompt = new StringBuilder();
        for (AiMessage message : request.messages()) {
            prompt.append(message.role()).append(": ").append(message.content()).append("\n\n");
        }
        prompt.append("assistant: ");
        body.addProperty("prompt", prompt.toString());
        return body;
    }

    @Override
    protected AiResponse parseResponse(String body) {
        JsonObject root = parseJsonObject(body);
        JsonArray choices = arrayMember(root, "choices");
        if (choices.size() == 0) {
            return new AiResponse("", List.of(), body);
        }
        JsonElement first = choices.get(0);
        if (!first.isJsonObject()) {
            return new AiResponse("", List.of(), body);
        }
        String text = stringMember(first.getAsJsonObject(), "text");
        return new AiResponse(text, List.of(), body);
    }

    @Override
    protected boolean supportsStreaming() {
        return true;
    }

    @Override
    protected AiResponse parseStreamingResponse(String body) {
        StringBuilder text = new StringBuilder();
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || !trimmed.startsWith("data:")) {
                continue;
            }
            String data = trimmed.substring("data:".length()).trim();
            if ("[DONE]".equals(data)) {
                break;
            }
            try {
                JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                JsonArray choices = arrayMember(chunk, "choices");
                if (choices.size() == 0 || !choices.get(0).isJsonObject()) {
                    continue;
                }
                text.append(stringMember(choices.get(0).getAsJsonObject(), "text"));
            } catch (Exception ignored) {
            }
        }
        return new AiResponse(text.toString(), List.of(), body);
    }
}
