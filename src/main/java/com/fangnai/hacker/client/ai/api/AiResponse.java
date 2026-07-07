package com.fangnai.hacker.client.ai.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AiResponse {
    private final String content;
    private final List<AiToolCall> toolCalls;
    private final String rawBody;

    public AiResponse(String content, List<AiToolCall> toolCalls, String rawBody) {
        this.content = content == null ? "" : content;
        this.toolCalls = Collections.unmodifiableList(new ArrayList<>(toolCalls == null ? List.of() : toolCalls));
        this.rawBody = rawBody == null ? "" : rawBody;
    }

    public String content() {
        return content;
    }

    public List<AiToolCall> toolCalls() {
        return toolCalls;
    }

    public String rawBody() {
        return rawBody;
    }
}
