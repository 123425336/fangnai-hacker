package com.fangnai.hacker.client.ai.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AiRequest {
    private final List<AiMessage> messages;

    public AiRequest(List<AiMessage> messages) {
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
    }

    public List<AiMessage> messages() {
        return messages;
    }
}
