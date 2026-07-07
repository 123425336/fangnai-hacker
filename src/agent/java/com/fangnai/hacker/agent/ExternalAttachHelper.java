package com.fangnai.hacker.agent;

import java.lang.reflect.Method;

public final class ExternalAttachHelper {
    private ExternalAttachHelper() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: ExternalAttachHelper <pid> <agentJar>");
        }
        String pid = args[0];
        String agentJar = args[1];

        Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
        Method attach = vmClass.getMethod("attach", String.class);
        Object vm = attach.invoke(null, pid);
        try {
            Method loadAgent = vmClass.getMethod("loadAgent", String.class, String.class);
            loadAgent.invoke(vm, agentJar, "external-attach");
        } finally {
            Method detach = vmClass.getMethod("detach");
            detach.invoke(vm);
        }
    }
}
