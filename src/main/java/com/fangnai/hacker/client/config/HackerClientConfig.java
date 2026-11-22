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
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Locale;

public final class HackerClientConfig {
    // 版本 6：调大默认的 token 数和各类轮数上限；版本 7：上下文记忆改成按 token 预算压缩
    // （迁移见 applyMissingDefaults）
    private static final int CURRENT_VERSION = 7;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("hacker-client.json");
    private static HackerClientConfig INSTANCE;
    /** 最近一次由本模组读取/写入配置文件后的修改时间，用来判断文件是否被外部手动编辑过。 */
    private static volatile long lastKnownModifiedMillis = -1L;

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

    public static synchronized void load() {
        HackerClientConfig loaded = null;
        JsonObject raw = null;
        boolean fileExists = Files.isRegularFile(CONFIG_PATH);
        if (fileExists) {
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
            // 文件存在但解析失败时先备份，防止下面写回默认值时把玩家的 Key 等配置覆盖掉
            if (fileExists) {
                backupBrokenFile();
            }
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

    public static synchronized void save() {
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
        lastKnownModifiedMillis = currentModifiedMillis();
    }

    /**
     * 如果配置文件在游戏运行期间被手动修改过，就重新读取它。
     * 解析失败（比如玩家正改到一半、JSON 不完整）时保留内存中的配置，不覆盖文件。
     *
     * @return 是否真的重新加载了配置
     */
    public static synchronized boolean reloadIfChangedOnDisk() {
        long modified = currentModifiedMillis();
        if (modified < 0L || modified == lastKnownModifiedMillis) {
            return false;
        }
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) {
                lastKnownModifiedMillis = modified;
                return false;
            }
        } catch (Exception ignored) {
            // JSON 暂时不合法：记录时间避免反复尝试，等玩家下次保存文件再读
            lastKnownModifiedMillis = modified;
            return false;
        }
        load();
        return true;
    }

    /** 配置文件的绝对路径（GUI 中展示给玩家）。 */
    public static Path path() {
        return CONFIG_PATH;
    }

    private static long currentModifiedMillis() {
        try {
            if (!Files.isRegularFile(CONFIG_PATH)) {
                return -1L;
            }
            FileTime time = Files.getLastModifiedTime(CONFIG_PATH);
            return time.toMillis();
        } catch (IOException ignored) {
            return -1L;
        }
    }

    private static void backupBrokenFile() {
        try {
            Path backup = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".broken");
            Files.copy(CONFIG_PATH, backup, StandardCopyOption.REPLACE_EXISTING);
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

        // 这三处是版本 5 引入的迁移，条件固定为 “旧于版本 5”，不能跟着 CURRENT_VERSION 走，
        // 否则每次升版本都会把玩家手动关掉的 stream 之类设置重置回默认值
        if (!aiRaw.has("timeoutSeconds") || (oldVersion < 5 && loaded.ai.timeoutSeconds == 60)) {
            loaded.ai.timeoutSeconds = defaults.ai.timeoutSeconds;
        }
        if (!aiRaw.has("maxTokens") || (oldVersion < 5 && loaded.ai.maxTokens == 1024)) {
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
        if (!aiRaw.has("autoRunAfterDefine")) {
            loaded.ai.autoRunAfterDefine = defaults.ai.autoRunAfterDefine;
        }
        if (!aiRaw.has("autoRunMaxRounds")) {
            loaded.ai.autoRunMaxRounds = defaults.ai.autoRunMaxRounds;
        }
        if (!aiRaw.has("autoRunObserveMillis")) {
            loaded.ai.autoRunObserveMillis = defaults.ai.autoRunObserveMillis;
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
        if (!aiRaw.has("stream") || (oldVersion < 5 && !loaded.ai.stream)) {
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
        if (!aiRaw.has("streamIncludeUsage")) {
            loaded.ai.streamIncludeUsage = defaults.ai.streamIncludeUsage;
        }
        if (!aiRaw.has("contextMemoryEnabled")) {
            loaded.ai.contextMemoryEnabled = defaults.ai.contextMemoryEnabled;
        }
        if (!aiRaw.has("contextMemoryMaxEntries")) {
            loaded.ai.contextMemoryMaxEntries = defaults.ai.contextMemoryMaxEntries;
        }
        if (!aiRaw.has("contextMemoryMaxTokens")) {
            loaded.ai.contextMemoryMaxTokens = defaults.ai.contextMemoryMaxTokens;
        }
        if (!aiRaw.has("contextMemoryCompress")) {
            loaded.ai.contextMemoryCompress = defaults.ai.contextMemoryCompress;
        }
        if (!aiRaw.has("contextMemoryEntryMaxChars")) {
            loaded.ai.contextMemoryEntryMaxChars = defaults.ai.contextMemoryEntryMaxChars;
        }

        // 版本 7：上下文记忆改成按 token 预算压缩，旧的“最多 20 条”不再是主要限制，
        // 仍然等于旧默认值 20 的话放宽成兜底的硬上限；玩家改过的值保持不变
        if (oldVersion < 7 && loaded.ai.contextMemoryMaxEntries == 20) {
            loaded.ai.contextMemoryMaxEntries = defaults.ai.contextMemoryMaxEntries;
        }

        // 版本 6：旧默认的 token 数和轮数上限太小，长任务跑不完。
        // 只迁移“仍然等于旧默认值”的字段；玩家手动改过的值保持不变
        if (oldVersion < 6) {
            if (loaded.ai.maxTokens == 8192) {
                loaded.ai.maxTokens = defaults.ai.maxTokens;
            }
            if (loaded.ai.autoRunMaxRounds == 10) {
                loaded.ai.autoRunMaxRounds = defaults.ai.autoRunMaxRounds;
            }
            if (loaded.ai.jarAnalysisWorkflowMaxAnalysisSteps == 5) {
                loaded.ai.jarAnalysisWorkflowMaxAnalysisSteps = defaults.ai.jarAnalysisWorkflowMaxAnalysisSteps;
            }
            if (loaded.ai.jarAnalysisWorkflowMaxAiTurns == 8) {
                loaded.ai.jarAnalysisWorkflowMaxAiTurns = defaults.ai.jarAnalysisWorkflowMaxAiTurns;
            }
            if (loaded.ai.jarAnalysisWorkflowMaxDefineAttempts == 3) {
                loaded.ai.jarAnalysisWorkflowMaxDefineAttempts = defaults.ai.jarAnalysisWorkflowMaxDefineAttempts;
            }
            if (loaded.ai.jarAnalysisWorkflowMaxCompileRepairAttempts == 5) {
                loaded.ai.jarAnalysisWorkflowMaxCompileRepairAttempts = defaults.ai.jarAnalysisWorkflowMaxCompileRepairAttempts;
            }
            if (loaded.ai.jarAnalysisWorkflowResultPreviewChars == 8000) {
                loaded.ai.jarAnalysisWorkflowResultPreviewChars = defaults.ai.jarAnalysisWorkflowResultPreviewChars;
            }
            if (loaded.ai.jarAnalysisWorkflowPromptMaxChars == 24000) {
                loaded.ai.jarAnalysisWorkflowPromptMaxChars = defaults.ai.jarAnalysisWorkflowPromptMaxChars;
            }
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
        public int maxTokens = 32768;
        public double temperature = 0.2D;
        public boolean stream = true;
        public boolean retryOnTransportFailure = true;
        public boolean disableHttpKeepAlive = true;
        public boolean promptCachingEnabled = true;
        /** 流式请求时要求中转站在最后返回 token 用量（含缓存命中），用来排查缓存命中率；个别不兼容的中转站可以关掉。 */
        public boolean streamIncludeUsage = true;
        public boolean contextMemoryEnabled = true;
        /** 历史上下文的 token 预算（估算值）：超出后把较早的记录压缩成一条摘要，而不是直接丢弃。 */
        public int contextMemoryMaxTokens = 500000;
        /** 超出预算时让模型把较早的记录压成摘要；关闭则直接丢弃最旧的记录。 */
        public boolean contextMemoryCompress = true;
        /** 记录条数的兜底硬上限，主要靠上面的 token 预算控制大小。 */
        public int contextMemoryMaxEntries = 2000;
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
        // 以下 4 个次数字段已不再作为硬上限使用：工作流出错时会自动把错误交给 AI 重试，只能由玩家手动取消
        public int jarAnalysisWorkflowMaxAnalysisSteps = 20;
        public int jarAnalysisWorkflowMaxAiTurns = 30;
        public int jarAnalysisWorkflowMaxDefineAttempts = 10;
        public int jarAnalysisWorkflowMaxCompileRepairAttempts = 10;
        public int jarAnalysisWorkflowResultPreviewChars = 16000;
        /** 工作流 prompt 的字符上限，超出会从末尾截断（末尾有“下一步只能做什么”的说明），所以不能太小。 */
        public int jarAnalysisWorkflowPromptMaxChars = 64000;
        /** 确认 define / 执行工具后自动执行，并把结果交给 AI 判断、没达成目标就自动修复。 */
        public boolean autoRunAfterDefine = true;
        /** 一次自动流程最多自动执行多少轮，用完先暂停等玩家说继续；0 表示不限制。 */
        public int autoRunMaxRounds = 30;
        /** 执行工具后继续收集聊天栏输出的时间（毫秒），用来捕获工具在服务端线程异步打印的结果。 */
        public int autoRunObserveMillis = 2500;
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
            maxTokens = Mth.clamp(maxTokens, 1, 131072);
            contextMemoryMaxTokens = Mth.clamp(contextMemoryMaxTokens, 1000, 4_000_000);
            contextMemoryMaxEntries = Mth.clamp(contextMemoryMaxEntries, 0, 20000);
            contextMemoryEntryMaxChars = Mth.clamp(contextMemoryEntryMaxChars, 256, 16000);
            temperature = Mth.clamp(temperature, 0.0D, 2.0D);
            maxPendingActions = Mth.clamp(maxPendingActions, 1, 128);
            pendingActionExpireSeconds = Mth.clamp(pendingActionExpireSeconds, 15, 3600);
            jarAnalysisTimeoutSeconds = Mth.clamp(jarAnalysisTimeoutSeconds, 5, 120);
            jarAnalysisMaxOutputChars = Mth.clamp(jarAnalysisMaxOutputChars, 4096, 524288);
            jarAnalysisMaxEntries = Mth.clamp(jarAnalysisMaxEntries, 100, 200000);
            jarAnalysisMaxJarMegabytes = Mth.clamp(jarAnalysisMaxJarMegabytes, 1, 2048);
            jarAnalysisWorkflowMaxAnalysisSteps = Mth.clamp(jarAnalysisWorkflowMaxAnalysisSteps, 1, 100);
            jarAnalysisWorkflowMaxAiTurns = Mth.clamp(jarAnalysisWorkflowMaxAiTurns, 1, 200);
            jarAnalysisWorkflowMaxDefineAttempts = Mth.clamp(jarAnalysisWorkflowMaxDefineAttempts, 1, 50);
            jarAnalysisWorkflowMaxCompileRepairAttempts = Mth.clamp(jarAnalysisWorkflowMaxCompileRepairAttempts, 0, 50);
            jarAnalysisWorkflowResultPreviewChars = Mth.clamp(jarAnalysisWorkflowResultPreviewChars, 1024, 65536);
            jarAnalysisWorkflowPromptMaxChars = Mth.clamp(jarAnalysisWorkflowPromptMaxChars, 4096, 131072);
            autoRunMaxRounds = Mth.clamp(autoRunMaxRounds, 0, 100);
            autoRunObserveMillis = Mth.clamp(autoRunObserveMillis, 0, 30000);
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
