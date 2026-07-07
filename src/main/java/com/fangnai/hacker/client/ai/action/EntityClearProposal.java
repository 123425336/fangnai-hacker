package com.fangnai.hacker.client.ai.action;

import com.fangnai.hacker.client.command.ChatFeedback;
import com.fangnai.hacker.client.generated.FangnaiRemove;

import java.util.Locale;

public final class EntityClearProposal extends ActionProposal {
    private final String mode;
    private final String target;
    private final boolean includePlayers;

    public EntityClearProposal(String mode, String target, boolean includePlayers, String reason, String expectedEffect) {
        super("entity_clear", reason, expectedEffect);
        this.mode = normalizeMode(mode);
        this.target = normalizeTarget(target);
        this.includePlayers = includePlayers;
    }

    public static EntityClearProposal fromPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        String compact = compact(prompt);
        if (compact.isBlank()) {
            return null;
        }

        if (isStopLoopPrompt(compact)) {
            return new EntityClearProposal("stop", "all", false,
                    "本地识别到停止循环清除请求。", "停止 FangnaiRemove 循环清除。 ");
        }

        boolean clearIntent = hasClearIntent(compact);
        boolean loopIntent = compact.contains("循环") || compact.contains("持续") || compact.contains("每tick")
                || compact.contains("每t") || compact.contains("loop") || compact.contains("repeat");
        if (!clearIntent && !loopIntent) {
            return null;
        }

        String target = inferTarget(compact);
        if (target.isBlank()) {
            if (loopIntent || compact.equals("清除") || compact.equals("执行清除")
                    || compact.contains("实体") || compact.contains("entity")) {
                target = "all";
            } else {
                return null;
            }
        }

        boolean includePlayers = inferIncludePlayers(compact);
        String mode = loopIntent ? "loop" : "once";
        String reason = "本地识别到实体清除请求：" + prompt.trim();
        String effect = ("loop".equals(mode) ? "每 tick 循环清除" : "清除一次")
                + "匹配目标 " + target + (includePlayers ? "，包含其他玩家但跳过自己。" : "，跳过所有玩家。");
        return new EntityClearProposal(mode, target, includePlayers, reason, effect);
    }

    public static boolean needsAiTargetResolution(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String compact = compact(prompt);
        if (compact.isBlank() || isStopLoopPrompt(compact)) {
            return false;
        }
        boolean clearIntent = hasClearIntent(compact);
        boolean loopIntent = compact.contains("循环") || compact.contains("持续") || compact.contains("每tick")
                || compact.contains("每t") || compact.contains("loop") || compact.contains("repeat");
        if (!clearIntent && !loopIntent) {
            return false;
        }
        return inferTarget(compact).isBlank();
    }

    public static String buildAiTargetResolutionPrompt(String prompt, String entityCatalog) {
        return "玩家原始请求：" + (prompt == null ? "" : prompt.trim()) + "\n\n"
                + "当前维度实体目录如下，优先使用 serverlevel；没有本地 serverlevel 时使用 clientlevel。目录列出了当前存在的非玩家实体类型、registry id、path、数量、显示名/translation key/class。\n"
                + entityCatalog + "\n\n"
                + "请根据玩家原始请求和实体目录自动匹配要清除的目标，然后必须调用 propose_entity_clear，不要只回答文字。\n"
                + "规则：\n"
                + "1. mode 根据玩家请求决定：普通清除=once；循环/持续/每tick清除=loop；停止/取消循环清除=stop。\n"
                + "2. target 必须是 registry id、registry path、或英文 path 片段。例：玩家说清除猪，若目录有 minecraft:pig 或 modid:*pig*，target 用 pig，这样会匹配所有 id/path/display/class 中包含 pig 的实体。\n"
                + "3. 如果玩家给中文生物名，请结合常识和目录映射到英文片段或具体 registry id，例如 猪 -> pig，羊 -> sheep。\n"
                + "4. 默认 includePlayers=false；只有玩家明确要求清除/包含玩家才可 true，但本地玩家永远跳过。\n"
                + "5. 如果目录中没有明显匹配项，选择最可能的英文片段作为 target，不要生成代码，不要执行原版命令。";
    }

    @Override
    public String summary() {
        String modeText = switch (mode) {
            case "loop" -> "循环清除";
            case "stop" -> "停止循环清除";
            default -> "清除一次";
        };
        String playerText = includePlayers ? "允许清除其他玩家，永远跳过自己" : "跳过所有玩家";
        return modeText + "实体；target=" + target + "；" + playerText
                + "；原因：" + reason() + "；效果：" + expectedEffect();
    }

    @Override
    public void execute() {
        String result = switch (mode) {
            case "loop" -> FangnaiRemove.startLoopClear(target, includePlayers);
            case "stop" -> FangnaiRemove.stopLoopClear();
            default -> FangnaiRemove.clearOnce(target, includePlayers);
        };
        ChatFeedback.info(result);
    }

    private static String normalizeMode(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("循环") || normalized.equals("loop") || normalized.equals("repeat")) {
            return "loop";
        }
        if (normalized.contains("停止") || normalized.contains("取消") || normalized.equals("stop") || normalized.equals("off")) {
            return "stop";
        }
        return "once";
    }

    private static String normalizeTarget(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? "all" : normalized;
    }

    private static boolean isStopLoopPrompt(String compact) {
        return (compact.contains("停止") || compact.contains("取消") || compact.contains("关闭") || compact.contains("stop"))
                && (compact.contains("循环清除") || compact.contains("清除") || compact.contains("loop"));
    }

    private static boolean hasClearIntent(String compact) {
        return compact.contains("清除") || compact.contains("删除") || compact.contains("移除")
                || compact.contains("clear") || compact.contains("remove") || compact.contains("delete");
    }

    private static String inferTarget(String compact) {
        if (compact.contains("玩家") || compact.contains("player")) {
            if (!containsAll(compact) || compact.contains("清除玩家") || compact.contains("删除玩家")
                    || compact.contains("移除玩家") || compact.contains("clearplayer") || compact.contains("removeplayer")) {
                return "players";
            }
        }
        if (compact.contains("羊") || compact.contains("sheep")) {
            return "sheep";
        }
        if (compact.contains("准星") || compact.contains("目标") || compact.contains("指向") || compact.contains("crosshair")) {
            return "crosshair";
        }
        if (containsAll(compact)) {
            return "all";
        }
        return "";
    }

    private static boolean containsAll(String compact) {
        return compact.contains("全部") || compact.contains("所有") || compact.contains("全清")
                || compact.contains("all") || compact.contains("everything") || compact.contains("实体");
    }

    private static boolean inferIncludePlayers(String compact) {
        if (!compact.contains("玩家") && !compact.contains("player")) {
            return false;
        }
        if (compact.contains("不要清") || compact.contains("不清") || compact.contains("别清")
                || compact.contains("跳过玩家") || compact.contains("不包括玩家") || compact.contains("不要玩家")
                || compact.contains("excludeplayer") || compact.contains("skipplayer")) {
            return false;
        }
        return compact.contains("玩家") || compact.contains("includeplayer") || compact.contains("includingplayer");
    }

    private static String compact(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
