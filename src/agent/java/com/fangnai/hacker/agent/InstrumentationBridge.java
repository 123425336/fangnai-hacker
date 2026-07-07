package com.fangnai.hacker.agent;

import java.lang.instrument.Instrumentation;

public final class InstrumentationBridge {
    private static volatile Instrumentation instrumentation;

    private InstrumentationBridge() {
    }

    static void setInstrumentation(Instrumentation value) {
        instrumentation = value;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static boolean isAvailable() {
        return instrumentation != null;
    }
}
