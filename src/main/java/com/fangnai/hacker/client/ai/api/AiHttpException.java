package com.fangnai.hacker.client.ai.api;

import java.net.URI;
import java.util.Locale;

/**
 * AI 接口返回非预期 HTTP 结果时抛出的异常，携带状态码、实际请求地址和响应片段，
 * 方便在聊天栏 / GUI 中给出针对性的排查提示。
 */
public final class AiHttpException extends IllegalStateException {
    private final int statusCode;
    private final URI uri;
    private final String bodyPreview;
    private final boolean htmlBody;

    public AiHttpException(int statusCode, URI uri, String bodyPreview, boolean htmlBody) {
        super((htmlBody ? "HTTP " + statusCode + " 返回了网页而不是 JSON" : "HTTP " + statusCode)
                + ": " + (bodyPreview == null ? "" : bodyPreview));
        this.statusCode = statusCode;
        this.uri = uri;
        this.bodyPreview = bodyPreview == null ? "" : bodyPreview;
        this.htmlBody = htmlBody;
    }

    public int statusCode() {
        return statusCode;
    }

    public URI uri() {
        return uri;
    }

    public String bodyPreview() {
        return bodyPreview;
    }

    public boolean htmlBody() {
        return htmlBody;
    }

    /** 根据状态码生成给玩家看的中文排查建议。 */
    public String hint() {
        String address = uri == null ? "" : uri.toString();
        if (htmlBody) {
            return "请求地址 " + address + " 返回的是网页（HTML），说明 Base URL 指向了网站前端而不是 API。"
                    + "请把 Base URL 改成服务商文档里的 API 地址，通常形如 https://域名/v1。";
        }
        return switch (statusCode) {
            case 400 -> "请求格式被拒绝（400）：可能是模型名填错、接口类型选错，或该模型不支持工具调用/流式输出。";
            case 401 -> "鉴权失败（401）：API Key 无效、已过期或没有填写。";
            case 403 -> "没有权限（403）：该 Key 无权访问此模型，或账户余额/分组受限。";
            case 404 -> "接口不存在（404）：" + address + " 找不到。检查 Base URL 是否缺少 /v1，或接口类型是否选错。";
            case 405 -> "该地址不接受 POST（405）：" + address
                    + " 很可能是网页/控制台地址而不是 API 地址。请填写服务商文档中的 API Base URL（通常形如 https://api.域名/v1）。";
            case 408, 504 -> "上游超时（" + statusCode + "）：可调高超时时间，或减少单次输出。";
            case 413 -> "请求体过大（413）：可清空上下文记忆后重试。";
            case 429 -> "请求过于频繁或额度用尽（429）：稍后再试，或检查账户余额。";
            case 500, 502, 503 -> "服务端错误（" + statusCode + "）：服务商或中转站暂时异常，稍后再试。";
            default -> "HTTP " + statusCode + "：请检查 Base URL、API Key 与模型名。";
        };
    }

    /** 粗略判断响应体是否为 HTML 网页。 */
    public static boolean looksLikeHtml(String body) {
        if (body == null) {
            return false;
        }
        String head = body.stripLeading();
        if (head.length() > 64) {
            head = head.substring(0, 64);
        }
        head = head.toLowerCase(Locale.ROOT);
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head");
    }
}
