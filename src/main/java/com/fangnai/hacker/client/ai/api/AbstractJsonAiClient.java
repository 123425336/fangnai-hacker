package com.fangnai.hacker.client.ai.api;

import com.fangnai.hacker.client.config.HackerClientConfig;
import com.mojang.logging.LogUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

abstract class AbstractJsonAiClient implements AiClient {
    private static final Logger LOGGER = LogUtils.getLogger();

    protected final HttpClient httpClient;
    protected final HackerClientConfig.AiSettings settings;
    protected final Gson gson = HackerClientConfig.gson();

    protected AbstractJsonAiClient(HttpClient httpClient, HackerClientConfig.AiSettings settings) {
        this.httpClient = httpClient;
        this.settings = settings;
    }

    @Override
    public CompletableFuture<AiResponse> send(AiRequest request) {
        HttpRequest httpRequest = buildHttpRequest(request);
        return sendOnce(httpRequest, 1).handle((response, throwable) -> {
            if (throwable != null && settings.retryOnTransportFailure && isRetryableTransportFailure(rootCause(throwable))) {
                LOGGER.warn("[Fangnai AI] HTTP transport failed once ({}), retrying with HTTP/1.1: {}",
                        rootCause(throwable).getClass().getSimpleName(), rootCause(throwable).getMessage());
                return sendOnce(buildHttpRequest(request), 2);
            }
            if (throwable != null) {
                return CompletableFuture.<AiResponse>failedFuture(rootCause(throwable));
            }
            return CompletableFuture.completedFuture(response);
        }).thenCompose(future -> future);
    }

    private HttpRequest buildHttpRequest(AiRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint())
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(settings.timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(buildBody(request))));
        String apiKey = settings.resolvedApiKey();
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    private CompletableFuture<AiResponse> sendOnce(HttpRequest httpRequest, int attempt) {
        LOGGER.info("[Fangnai AI] HTTP request attempt {}: {} timeout={}s version={}", attempt,
                httpRequest.uri(), settings.timeoutSeconds, httpRequest.version().orElse(HttpClient.Version.HTTP_1_1));
        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    LOGGER.info("[Fangnai AI] HTTP response attempt {}: {} status={} bodyChars={}", attempt,
                            httpRequest.uri(), response.statusCode(), response.body() == null ? 0 : response.body().length());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("HTTP " + response.statusCode() + ": " + truncate(response.body(), 500));
                    }
                    return settings.stream && supportsStreaming() ? parseStreamingResponse(response.body()) : parseResponse(response.body());
                });
    }

    protected abstract String endpointPath();

    protected abstract JsonObject buildBody(AiRequest request);

    protected abstract AiResponse parseResponse(String body);

    protected boolean supportsStreaming() {
        return false;
    }

    protected AiResponse parseStreamingResponse(String body) {
        return parseResponse(body);
    }

    protected URI endpoint() {
        String base = settings.baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + endpointPath());
    }

    protected JsonArray messagesToJson(AiRequest request) {
        JsonArray messages = new JsonArray();
        for (AiMessage message : request.messages()) {
            JsonObject object = new JsonObject();
            object.addProperty("role", message.role());
            object.addProperty("content", message.content());
            messages.add(object);
        }
        return messages;
    }

    protected JsonObject baseBody() {
        JsonObject body = new JsonObject();
        if (!settings.model.isBlank()) {
            body.addProperty("model", settings.model);
        }
        body.addProperty("max_tokens", settings.maxTokens);
        body.addProperty("temperature", settings.temperature);
        body.addProperty("stream", settings.stream && supportsStreaming());
        return body;
    }

    protected JsonObject parseJsonObject(String body) {
        JsonElement element = JsonParser.parseString(body);
        if (!element.isJsonObject()) {
            throw new IllegalStateException("Response was not a JSON object");
        }
        return element.getAsJsonObject();
    }

    protected String stringMember(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && !element.isJsonNull() ? element.getAsString() : "";
    }

    protected JsonObject objectMember(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    protected JsonArray arrayMember(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    protected List<AiToolCall> parseOpenAiToolCalls(JsonObject message) {
        List<AiToolCall> calls = new ArrayList<>();
        JsonArray toolCalls = arrayMember(message, "tool_calls");
        for (JsonElement element : toolCalls) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject call = element.getAsJsonObject();
            JsonObject function = objectMember(call, "function");
            JsonObject arguments = new JsonObject();
            String rawArguments = stringMember(function, "arguments");
            if (!rawArguments.isBlank()) {
                try {
                    JsonElement parsed = JsonParser.parseString(rawArguments);
                    if (parsed.isJsonObject()) {
                        arguments = parsed.getAsJsonObject();
                    }
                } catch (Exception ignored) {
                    arguments.addProperty("raw", rawArguments);
                }
            }
            calls.add(new AiToolCall(stringMember(call, "id"), stringMember(function, "name"), arguments));
        }
        return calls;
    }

    protected String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isRetryableTransportFailure(Throwable throwable) {
        if (throwable instanceof java.io.EOFException) {
            return true;
        }
        if (throwable instanceof java.io.IOException) {
            String message = throwable.getMessage();
            if (message == null) {
                return true;
            }
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("reset")
                    || lower.contains("closed")
                    || lower.contains("eof")
                    || lower.contains("header parser received no bytes")
                    || lower.contains("no bytes")
                    || lower.contains("unexpected end");
        }
        Throwable cause = throwable.getCause();
        return cause != null && cause != throwable && isRetryableTransportFailure(cause);
    }
}
