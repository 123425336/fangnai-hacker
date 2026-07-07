package com.fangnai.hacker.agent;

import java.lang.instrument.Instrumentation;

public final class HackerAgent {
    private HackerAgent() {
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        InstrumentationBridge.setInstrumentation(instrumentation);
    }
}
