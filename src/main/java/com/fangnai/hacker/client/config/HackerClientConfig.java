package com.fangnai.hacker.client.config;

import com.fangnai.hacker.client.combat.TargetPriority;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Mth;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class HackerClientConfig {
    private static final int CURRENT_VERSION = 5;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("hacker-client.json");
    private static HackerClientConfig INSTANCE;

    public int version = CURRENT_VERSION;
    public double guiPanelX = 20;
    public double guiPanelY = 40;
    public KillAreaSettings killArea = new KillAreaSettings();
    public AiSettings ai = new AiSettings();

    private HackerClientConfig() {
    }

    public static HackerClientConfig get() {
        if (INSTANCE == null) {
            load();
        }
        return INSTANCE;
    }

    public static KillAreaSettings killArea() {
        return get().killArea;
    }

    public static AiSettings ai() {
        return get().ai;
    }

    public static void load() {
        HackerClientConfig loaded = null;
        JsonObject raw = null;
        if (Files.isRegularFile(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                JsonElement element = JsonParser.parseReader(reader);
                if (element != null && element.isJsonObject()) {
                    raw = element.getAsJsonObject();
                    loaded = GSON.fromJson(raw, HackerClientConfig.class);
                }
            } catch (Exception ignored) {
                loaded = null;
            }
        }

        if (loaded == null) {
            loaded = new HackerClientConfig();
        }
        if (loaded.killArea == null) {
            loaded.killArea = new KillAreaSettings();
        }
        if (loaded.ai == null) {
            loaded.ai = new AiSettings();
        }

        applyMissingDefaults(raw, loaded);
        INSTANCE = loaded;
        INSTANCE.clamp();
        save();
    }

    public static void save() {
        if (INSTANCE == null) {
            return;
        }

        INSTANCE.clamp();
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(INSTANCE, writer);
            }
        } catch (IOException ignored) {
        }
    }

    public static Gson gson() {
        return GSON;
    }

    public void clamp() {
        version = CURRENT_VERSION;
        if (killArea == null) {
            killArea = new KillAreaSettings();
        }
        if (ai == null) {
            ai = new AiSettings();
        }
        killArea.clamp();
        ai.clamp();
    }

    private static void applyMissingDefaults(JsonObject raw, HackerClientConfig loaded) {
        HackerClientConfig defaults = new HackerClientConfig();
        if (raw == null) {
            return;
        }
        int oldVersion = intMember(raw, "version", loaded.version);
        JsonObject aiRaw = objectMember(raw, "ai");
        if (aiRaw == null) {
            loaded.ai = defaults.ai;
            return;
        }

        if (!aiRaw.has("timeoutSeconds") || (oldVersion < CURRENT_VERSION && loaded.ai.timeoutSeconds == 60)) {
            loaded.ai.timeoutSeconds = defaults.ai.timeoutSeconds;
        }
        if (!aiRaw.has("maxTokens") || (oldVersion < CURRENT_VERSION && loaded.ai.maxTokens == 1024)) {
            loaded.ai.maxTokens = defaults.ai.maxTokens;
        }
        if (!aiRaw.has("confirmJarAnalysis")) {
            loaded.ai.confirmJarAnalysis = defaults.ai.confirmJarAnalysis;
        }
        if (!aiRaw.has("jarAnalysisTimeoutSeconds")) {
            loaded.ai.jarAnalysisTimeoutSeconds = defaults.ai.jarAnalysisTimeoutSeconds;
        }
        if (!aiRaw.has("jarAnalysisMaxOutputChars")) {
            loaded.ai.jarAnalysisMaxOutputChars = defaults.ai.jarAnalysisMaxOutputChars;
        }
        if (!aiRaw.has("jarAnalysisMaxEntries")) {
            loaded.ai.jarAnalysisMaxEntries = defaults.ai.jarAnalysisMaxEntries;
        }
        if (!aiRaw.has("jarAnalysisMaxJarMegabytes")) {
            loaded.ai.jarAnalysisMaxJarMegabytes = defaults.ai.jarAnalysisMaxJarMegabytes;
        }
        if (!aiRaw.has("jarAnalysisWorkflowEnabled")) {
            loaded.ai.jarAnalysisWorkflowEnabled = defaults.ai.jarAnalysisWorkflowEnabled;
        }
        if (!aiRaw.has("jarAnalysisWorkflowMaxAnalysisSteps")) {
            loaded.ai.jarAnalysisWorkflowMaxAnalysisSteps = defaults.ai.jarAnalysisWorkflowMaxAnalysisSteps;
        }
        if (!aiRaw.has("jarAnalysisWorkflowMaxAiTurns")) {
            loaded.ai.jarAnalysisWorkflowMaxAiTurns = defaults.ai.jarAnalysisWorkflowMaxAiTurns;
        }
        if (!aiRaw.has("jarAnalysisWorkflowMaxDefineAttempts")) {
            loaded.ai.jarAnalysisWorkflowMaxDefineAttempts = defaults.ai.jarAnalysisWorkflowMaxDefineAttempts;
        }
        if (!aiRaw.has("jarAnalysisWorkflowMaxCompileRepairAttempts")) {
            loaded.ai.jarAnalysisWorkflowMaxCompileRepairAttempts = defaults.ai.jarAnalysisWorkflowMaxCompileRepairAttempts;
        }
        if (!aiRaw.has("jarAnalysisWorkflowResultPreviewChars")) {
            loaded.ai.jarAnalysisWorkflowResultPreviewChars = defaults.ai.jarAnalysisWorkflowResultPreviewChars;
        }
        if (!aiRaw.has("jarAnalysisWorkflowPromptMaxChars")) {
            loaded.ai.jarAnalysisWorkflowPromptMaxChars = defaults.ai.jarAnalysisWorkflowPromptMaxChars;
        }
        if (!aiRaw.has("confirmClassDefine")) {
            loaded.ai.confirmClassDefine = defaults.ai.confirmClassDefine;
        }
        if (!aiRaw.has("classDefineMaxBytes")) {
            loaded.ai.classDefineMaxBytes = defaults.ai.classDefineMaxBytes;
        }
        if (!aiRaw.has("classDefineAllowedPackage")) {
            loaded.ai.classDefineAllowedPackage = defaults.ai.classDefineAllowedPackage;
        }
        if (!aiRaw.has("compileDefineTimeoutSeconds")) {
            loaded.ai.compileDefineTimeoutSeconds = defaults.ai.compileDefineTimeoutSeconds;
        }
        if (!aiRaw.has("generatedSourceMaxChars")) {
            loaded.ai.generatedSourceMaxChars = defaults.ai.generatedSourceMaxChars;
        }
        if (!aiRaw.has("stream") || (oldVersion < CURRENT_VERSION && !loaded.ai.stream)) {
            loaded.ai.stream = defaults.ai.stream;
        }
        if (!aiRaw.has("retryOnTransportFailure")) {
            loaded.ai.retryOnTransportFailure = defaults.ai.retryOnTransportFailure;
        }
        if (!aiRaw.has("disableHttpKeepAlive")) {
            loaded.ai.disableHttpKeepAlive = defaults.ai.disableHttpKeepAlive;
        }
        if (!aiRaw.has("promptCachingEnabled")) {
            loaded.ai.promptCachingEnabled = defaults.ai.promptCachingEnabled;
        }
        if (!aiRaw.has("contextMemoryEnabled")) {
            loaded.ai.contextMemoryEnabled = defaults.ai.contextMemoryEnabled;
        }
        if (!aiRaw.has("contextMemoryMaxEntries")) {
            loaded.ai.contextMemoryMaxEntries = defaults.ai.contextMemoryMaxEntries;
        }
        if (!aiRaw.has("contextMemoryEntryMaxChars")) {
            loaded.ai.contextMemoryEntryMaxChars = defaults.ai.contextMemoryEntryMaxChars;
        }
    }

    private static JsonObject objectMember(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static int intMember(JsonObject object, String name, int fallback) {
        JsonElement element = object.get(name);
        try {
            return element != null && !element.isJsonNull() ? element.getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static final class KillAreaSettings {
        public double reach = 3.0D;
        public double cps = 4.0D;
        public boolean targetPlayers = true;
        public boolean targetHostiles = true;
        public boolean targetNeutral = false;
        public boolean targetPassive = false;
        public boolean requireLineOfSight = true;
        public boolean respectAttackCooldown = true;
        public boolean pauseWhileUsingItem = true;
        public boolean critHit = true;
        public boolean noKnockback = false;
        public double fovDegrees = 120.0D;
        public TargetPriority priority = TargetPriority.NEAREST;

        public void clamp() {
            reach = Mth.clamp(reach, 2.0D, 6.0D);
            cps = Mth.clamp(cps, 1.0D, 15.0D);
            fovDegrees = Mth.clamp(fovDegrees, 30.0D, 360.0D);
            if (priority == null) {
                priority = TargetPriority.NEAREST;
            }
        }
    }

    public static final class AiSettings {
        public boolean enabled = false;
        public String baseUrl = "http://localhost:11434/v1";
        public String apiKey = "";
        public String apiKeyEnv = "";
        public String model = "";
        public String systemPrompt = "你是猫娘乐园里的香菜，芳乃 Hacker 的游戏内助手。你可以回答问题，也可以提出需要玩家确认的操作，你的回复需以喵结尾。";
        public EndpointMode endpointMode = EndpointMode.OPENAI_CHAT_COMPLETIONS;
        public int timeoutSeconds = 180;
        public int maxTokens = 8192;
        public double temperature = 0.2D;
        public boolean stream = true;
        public boolean retryOnTransportFailure = true;
        public boolean disableHttpKeepAlive = true;
        public boolean promptCachingEnabled = true;
        public boolean contextMemoryEnabled = true;
        public int contextMemoryMaxEntries = 20;
        public int contextMemoryEntryMaxChars = 4000;
        public boolean confirmCommands = true;
        public boolean confirmTransforms = true;
        public boolean confirmJarAnalysis = true;
        public boolean confirmClassDefine = true;
        public String toolDirectory = "tool";
        public int maxPendingActions = 16;
        public int pendingActionExpireSeconds = 180;
        public int jarAnalysisTimeoutSeconds = 20;
        public int jarAnalysisMaxOutputChars = 65536;
        public int jarAnalysisMaxEntries = 50000;
        public int jarAnalysisMaxJarMegabytes = 256;
        public boolean jarAnalysisWorkflowEnabled = true;
        public int jarAnalysisWorkflowMaxAnalysisSteps = 5;
        public int jarAnalysisWorkflowMaxAiTurns = 8;
        public int jarAnalysisWorkflowMaxDefineAttempts = 3;
        public int jarAnalysisWorkflowMaxCompileRepairAttempts = 5;
        public int jarAnalysisWorkflowResultPreviewChars = 8000;
        public int jarAnalysisWorkflowPromptMaxChars = 24000;
        public int classDefineMaxBytes = 262144;
        public String classDefineAllowedPackage = "com.fangnai.hacker.generated";
        public int compileDefineTimeoutSeconds = 30;
        public int generatedSourceMaxChars = 65536;

        public void clamp() {
            baseUrl = clean(baseUrl, "http://localhost:11434/v1");
            apiKey = clean(apiKey, "");
            apiKeyEnv = clean(apiKeyEnv, "");
            model = clean(model, "");
            systemPrompt = clean(systemPrompt, "");
            toolDirectory = clean(toolDirectory, "tool");
            classDefineAllowedPackage = clean(classDefineAllowedPackage, "com.fangnai.hacker.generated");
            if (endpointMode == null) {
                endpointMode = EndpointMode.OPENAI_CHAT_COMPLETIONS;
            }
            timeoutSeconds = Mth.clamp(timeoutSeconds, 10, 600);
            maxTokens = Mth.clamp(maxTokens, 1, 32768);
            contextMemoryMaxEntries = Mth.clamp(contextMemoryMaxEntries, 0, 100);
            contextMemoryEntryMaxChars = Mth.clamp(contextMemoryEntryMaxChars, 256, 16000);
            temperature = Mth.clamp(temperature, 0.0D, 2.0D);
            maxPendingActions = Mth.clamp(maxPendingActions, 1, 128);
            pendingActionExpireSeconds = Mth.clamp(pendingActionExpireSeconds, 15, 3600);
            jarAnalysisTimeoutSeconds = Mth.clamp(jarAnalysisTimeoutSeconds, 5, 120);
            jarAnalysisMaxOutputChars = Mth.clamp(jarAnalysisMaxOutputChars, 4096, 524288);
            jarAnalysisMaxEntries = Mth.clamp(jarAnalysisMaxEntries, 100, 200000);
            jarAnalysisMaxJarMegabytes = Mth.clamp(jarAnalysisMaxJarMegabytes, 1, 2048);
            jarAnalysisWorkflowMaxAnalysisSteps = Mth.clamp(jarAnalysisWorkflowMaxAnalysisSteps, 1, 20);
            jarAnalysisWorkflowMaxAiTurns = Mth.clamp(jarAnalysisWorkflowMaxAiTurns, 1, 30);
            jarAnalysisWorkflowMaxDefineAttempts = Mth.clamp(jarAnalysisWorkflowMaxDefineAttempts, 1, 10);
            jarAnalysisWorkflowMaxCompileRepairAttempts = Mth.clamp(jarAnalysisWorkflowMaxCompileRepairAttempts, 0, 20);
            jarAnalysisWorkflowResultPreviewChars = Mth.clamp(jarAnalysisWorkflowResultPreviewChars, 1024, 65536);
            jarAnalysisWorkflowPromptMaxChars = Mth.clamp(jarAnalysisWorkflowPromptMaxChars, 4096, 131072);
            classDefineMaxBytes = Mth.clamp(classDefineMaxBytes, 1024, 1048576);
            compileDefineTimeoutSeconds = Mth.clamp(compileDefineTimeoutSeconds, 5, 120);
            generatedSourceMaxChars = Mth.clamp(generatedSourceMaxChars, 1024, 262144);
        }

        public String resolvedApiKey() {
            if (!apiKey.isBlank()) {
                return apiKey;
            }
            if (!apiKeyEnv.isBlank()) {
                String value = System.getenv(apiKeyEnv);
                if (value != null) {
                    return value;
                }
            }
            return "";
        }

        public String maskedApiKey() {
            String value = resolvedApiKey();
            if (value.isBlank()) {
                return "not set";
            }
            if (value.length() <= 4) {
                return "****";
            }
            return "****" + value.substring(value.length() - 4);
        }

        private static String clean(String value, String fallback) {
            if (value == null) {
                return fallback;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? fallback : trimmed;
        }
    }

    public enum EndpointMode {
        OPENAI_CHAT_COMPLETIONS,
        OPENAI_COMPLETIONS,
        MESSAGES;

        public static EndpointMode byName(String name) {
            if (name == null) {
                return OPENAI_CHAT_COMPLETIONS;
            }
            try {
                return EndpointMode.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return OPENAI_CHAT_COMPLETIONS;
            }
        }
    }
}
