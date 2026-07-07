package com.fangnai.hacker.client.ai.action;

import com.fangnai.hacker.client.ai.api.AiToolCall;
import com.fangnai.hacker.client.config.HackerClientConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public abstract class ActionProposal {
    private final String id;
    private final String type;
    private final String reason;
    private final String expectedEffect;
    private final long createdAtMillis;

    protected ActionProposal(String type, String reason, String expectedEffect) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.type = type;
        this.reason = blankToDefault(reason, "未提供原因");
        this.expectedEffect = blankToDefault(expectedEffect, "未提供预期效果");
        this.createdAtMillis = System.currentTimeMillis();
    }

    public String id() {
        return id;
    }

    public String type() {
        return type;
    }

    public String reason() {
        return reason;
    }

    public String expectedEffect() {
        return expectedEffect;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public boolean isExpired() {
        long ttl = HackerClientConfig.ai().pendingActionExpireSeconds * 1000L;
        return System.currentTimeMillis() - createdAtMillis > ttl;
    }

    public abstract String summary();

    public abstract void execute();

    protected static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && !element.isJsonNull() ? element.getAsString() : "";
    }

    protected static boolean bool(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isString()) {
                String value = primitive.getAsString().trim().toLowerCase(Locale.ROOT);
                return "true".equals(value) || "yes".equals(value) || "1".equals(value)
                        || "包含".equals(value) || "包括".equals(value);
            }
        }
        return false;
    }

    protected static List<String> stringList(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item != null && !item.isJsonNull()) {
                    values.add(item.getAsString());
                }
            }
            return values;
        }
        if (element.isJsonPrimitive()) {
            String raw = element.getAsString();
            for (String line : raw.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isBlank()) {
                    values.add(trimmed);
                }
            }
        }
        return values;
    }

    protected static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static ActionProposal fromToolCall(AiToolCall call) {
        String name = call.name().toLowerCase(Locale.ROOT);
        JsonObject args = call.arguments();
        return switch (name) {
            case "propose_vanilla_command" -> new VanillaCommandProposal(
                    string(args, "command"),
                    string(args, "reason"),
                    string(args, "expectedEffect")
            );
            case "propose_vanilla_commands" -> new VanillaCommandsProposal(
                    stringList(args, "commands"),
                    string(args, "reason"),
                    string(args, "expectedEffect")
            );
            case "propose_void_return_transform" -> new TransformProposal(
                    string(args, "targetPattern"),
                    string(args, "methodPattern"),
                    string(args, "reason"),
                    string(args, "expectedEffect")
            );
            case "propose_default_return_transform" -> new TransformProposal(
                    string(args, "targetPattern"),
                    string(args, "methodPattern"),
                    string(args, "returnMode"),
                    string(args, "reason"),
                    string(args, "expectedEffect")
            );
            case "execute_saved_tool" -> new SavedToolProposal(
                    string(args, "toolId"),
                    string(args, "input"),
                    string(args, "reason"),
                    string(args, "expectedEffect")
            );
            case "propose_entity_clear" -> new EntityClearProposal(
                    string(args, "mode"),
                    string(args, "target"),
                    bool(args, "includePlayers"),
                    string(args, "reason"),
                    string(args, "expectedEffect")
            );
            case "propose_jar_analysis" -> new JarAnalysisProposal(
                    string(args, "mode"),
                    string(args, "jarSelector"),
                    string(args, "classPattern"),
                    string(args, "memberPattern"),
                    string(args, "searchText"),
                    string(args, "reason"),
                    string(args, "expectedEffect")
            );
            case "propose_define_generated_class" -> new ClassDefineProposal(
                    string(args, "className"),
                    string(args, "base64ClassBytes"),
                    string(args, "sha256"),
                    string(args, "reason"),
                    string(args, "expectedEffect")
            );
            case "propose_compile_define_java_class" -> new JavaSourceDefineProposal(
                    string(args, "className"),
                    string(args, "javaSource"),
                    string(args, "sourceSha256"),
                    string(args, "reason"),
                    string(args, "expectedEffect")
            );
            default -> null;
        };
    }

    public static ActionProposal fromJsonEnvelope(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(trimmed);
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject object = element.getAsJsonObject();
            String action = string(object, "action").toLowerCase(Locale.ROOT);
            return switch (action) {
                case "command", "vanilla_command" -> new VanillaCommandProposal(
                        string(object, "command"),
                        string(object, "reason"),
                        string(object, "expectedEffect")
                );
                case "commands", "vanilla_commands", "command_batch" -> new VanillaCommandsProposal(
                        stringList(object, "commands"),
                        string(object, "reason"),
                        string(object, "expectedEffect")
                );
                case "void_return_transform", "transform" -> new TransformProposal(
                        string(object, "targetPattern"),
                        string(object, "methodPattern"),
                        string(object, "reason"),
                        string(object, "expectedEffect")
                );
                case "default_return_transform", "return_constant_transform" -> new TransformProposal(
                        string(object, "targetPattern"),
                        string(object, "methodPattern"),
                        string(object, "returnMode"),
                        string(object, "reason"),
                        string(object, "expectedEffect")
                );
                case "saved_tool" -> new SavedToolProposal(
                        string(object, "toolId"),
                        string(object, "input"),
                        string(object, "reason"),
                        string(object, "expectedEffect")
                );
                case "entity_clear", "clear_entities" -> new EntityClearProposal(
                        string(object, "mode"),
                        string(object, "target"),
                        bool(object, "includePlayers"),
                        string(object, "reason"),
                        string(object, "expectedEffect")
                );
                case "jar_analysis", "decompile_jar" -> new JarAnalysisProposal(
                        string(object, "mode"),
                        string(object, "jarSelector"),
                        string(object, "classPattern"),
                        string(object, "memberPattern"),
                        string(object, "searchText"),
                        string(object, "reason"),
                        string(object, "expectedEffect")
                );
                case "define_generated_class", "define_class" -> new ClassDefineProposal(
                        string(object, "className"),
                        string(object, "base64ClassBytes"),
                        string(object, "sha256"),
                        string(object, "reason"),
                        string(object, "expectedEffect")
                );
                case "compile_define_java_class", "define_java_source" -> new JavaSourceDefineProposal(
                        string(object, "className"),
                        string(object, "javaSource"),
                        string(object, "sourceSha256"),
                        string(object, "reason"),
                        string(object, "expectedEffect")
                );
                default -> null;
            };
        } catch (Exception ignored) {
            return null;
        }
    }
}
