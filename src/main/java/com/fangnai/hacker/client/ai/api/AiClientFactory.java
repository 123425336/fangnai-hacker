package com.fangnai.hacker.client.ai.api;

import com.fangnai.hacker.client.config.HackerClientConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;

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

    /** 各接口类型追加在 Base URL 后面的路径。 */
    public static String endpointPath(HackerClientConfig.EndpointMode mode) {
        if (mode == null) {
            return "/chat/completions";
        }
        return switch (mode) {
            case OPENAI_COMPLETIONS -> "/completions";
            case MESSAGES -> "/messages";
            case OPENAI_CHAT_COMPLETIONS -> "/chat/completions";
        };
    }

    /** 计算当前配置最终会请求的完整地址（GUI 预览与实际请求共用同一套规则）。 */
    public static String resolveEndpoint(HackerClientConfig.AiSettings settings) {
        return resolveEndpoint(settings.baseUrl, settings.endpointMode);
    }

    /**
     * 规范化 Base URL 并拼接接口路径：
     * 1. 去掉首尾空白与末尾多余的 "/"；
     * 2. 没写协议时默认补 https://；
     * 3. 如果用户直接粘贴了完整接口地址（已经以接口路径结尾），就原样使用，避免路径重复；
     * 4. 只有域名、没有任何路径时自动补 /v1（绝大多数兼容接口都挂在 /v1 下，
     *    缺少它时常见的结果是返回网页 HTML、404 或 405）。
     */
    public static String resolveEndpoint(String baseUrl, HackerClientConfig.EndpointMode mode) {
        return resolveEndpointWithPath(baseUrl, endpointPath(mode));
    }

    /** 按上面的规则把 Base URL 与指定接口路径拼成完整地址。 */
    public static String resolveEndpointWithPath(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            return path;
        }
        if (!base.contains("://")) {
            base = "https://" + base;
        }
        if (base.toLowerCase(Locale.ROOT).endsWith(path)) {
            return base;
        }
        if (uriPath(base).isEmpty()) {
            base = base + "/v1";
        }
        return base + path;
    }

    private static String uriPath(String base) {
        try {
            String path = URI.create(base).getPath();
            if (path == null) {
                return "";
            }
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return path;
        } catch (IllegalArgumentException ignored) {
            // 地址格式异常时不做自动补全，交给后续请求报错
            return "?";
        }
    }
}
