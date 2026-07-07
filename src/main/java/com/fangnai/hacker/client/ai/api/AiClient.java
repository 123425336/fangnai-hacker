package com.fangnai.hacker.client.ai.api;

import java.util.concurrent.CompletableFuture;

public interface AiClient {
    CompletableFuture<AiResponse> send(AiRequest request);
}
