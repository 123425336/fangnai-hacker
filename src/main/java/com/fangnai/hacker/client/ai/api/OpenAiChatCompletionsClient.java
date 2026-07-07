package com.fangnai.hacker.client.ai.api;

import com.fangnai.hacker.client.config.HackerClientConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

public final class OpenAiChatCompletionsClient extends AbstractJsonAiClient {
    public OpenAiChatCompletionsClient(HttpClient httpClient, HackerClientConfig.AiSettings settings) {
        super(httpClient, settings);
    }

    @Override
    protected String endpointPath() {
        return "/chat/completions";
    }

    @Override
    protected JsonObject buildBody(AiRequest request) {
        JsonObject body = baseBody();
        body.add("messages", messagesToJson(request));
        body.add("tools", ToolSchemaJson.openAiTools());
        body.addProperty("tool_choice", "auto");
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
        JsonObject message = objectMember(first.getAsJsonObject(), "message");
        return new AiResponse(stringMember(message, "content"), parseOpenAiToolCalls(message), body);
    }

    @Override
    protected boolean supportsStreaming() {
        return true;
    }

    @Override
    protected AiResponse parseStreamingResponse(String body) {
        StringBuilder content = new StringBuilder();
        List<AiToolCall> toolCalls = new ArrayList<>();
        List<ToolCallAccumulator> accumulators = new ArrayList<>();
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
                JsonObject delta = objectMember(choices.get(0).getAsJsonObject(), "delta");
                content.append(stringMember(delta, "content"));
                JsonArray deltas = arrayMember(delta, "tool_calls");
                for (JsonElement element : deltas) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject toolDelta = element.getAsJsonObject();
                    int index = intMember(toolDelta, "index", accumulators.size());
                    while (accumulators.size() <= index) {
                        accumulators.add(new ToolCallAccumulator());
                    }
                    ToolCallAccumulator accumulator = accumulators.get(index);
                    String id = stringMember(toolDelta, "id");
                    if (!id.isBlank()) {
                        accumulator.id = id;
                    }
                    JsonObject function = objectMember(toolDelta, "function");
                    String name = stringMember(function, "name");
                    if (!name.isBlank()) {
                        accumulator.name = name;
                    }
                    accumulator.arguments.append(stringMember(function, "arguments"));
                }
            } catch (Exception ignored) {
            }
        }
        for (ToolCallAccumulator accumulator : accumulators) {
            if (accumulator.name == null || accumulator.name.isBlank()) {
                continue;
            }
            JsonObject arguments = new JsonObject();
            if (!accumulator.arguments.isEmpty()) {
                try {
                    JsonElement parsed = JsonParser.parseString(accumulator.arguments.toString());
                    if (parsed.isJsonObject()) {
                        arguments = parsed.getAsJsonObject();
                    }
                } catch (Exception ignored) {
                    arguments.addProperty("raw", accumulator.arguments.toString());
                }
            }
            toolCalls.add(new AiToolCall(accumulator.id, accumulator.name, arguments));
        }
        return new AiResponse(content.toString(), toolCalls, body);
    }

    private int intMember(JsonObject object, String name, int fallback) {
        JsonElement element = object.get(name);
        try {
            return element != null && !element.isJsonNull() ? element.getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static final class ToolCallAccumulator {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();
    }
}
