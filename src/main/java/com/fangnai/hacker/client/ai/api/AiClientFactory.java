package com.fangnai.hacker.client.ai.api;

import com.fangnai.hacker.client.config.HackerClientConfig;

import java.net.http.HttpClient;
import java.time.Duration;

public final class AiClientFactory {
    private AiClientFactory() {
    }

    public static AiClient create(HackerClientConfig.AiSettings settings) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(Math.min(settings.timeoutSeconds, 30)))
                .build();
        return switch (settings.endpointMode) {
            case OPENAI_COMPLETIONS -> new OpenAiCompletionsClient(httpClient, settings);
            case MESSAGES -> new MessagesClient(httpClient, settings);
            case OPENAI_CHAT_COMPLETIONS -> new OpenAiChatCompletionsClient(httpClient, settings);
        };
    }
}
