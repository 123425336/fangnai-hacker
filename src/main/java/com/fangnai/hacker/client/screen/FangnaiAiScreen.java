package com.fangnai.hacker.client.screen;

import com.fangnai.hacker.client.ai.action.ActionProposal;
import com.fangnai.hacker.client.ai.action.PendingActionRegistry;
import com.fangnai.hacker.client.ai.api.AiClientFactory;
import com.fangnai.hacker.client.ai.api.AiHttpException;
import com.fangnai.hacker.client.ai.api.AiMessage;
import com.fangnai.hacker.client.ai.api.AiRequest;
import com.fangnai.hacker.client.ai.session.AiChatLog;
import com.fangnai.hacker.client.ai.session.AiConversationManager;
import com.fangnai.hacker.client.command.ChatFeedback;
import com.fangnai.hacker.client.command.FangnaiHackerCommands;
import com.fangnai.hacker.client.config.HackerClientConfig;
import com.fangnai.hacker.client.render.HudBlurRenderer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * 香草 AI 对话界面（/fangnaigui 打开）。
 * 对话页：气泡式消息列表 + 待确认操作卡片 + 快捷指令 + 输入框；
 * 设置页：直接编辑接口地址、密钥、模型等，并可实时预览最终请求地址、测试连接。
 */
public class FangnaiAiScreen extends Screen {

    // ── 调色板 ────────────────────────────────────────────────────────────
    private static final int ACCENT = 0xFF00C8FF;      // 主色：青
    private static final int PINK = 0xFFFF8FC8;        // 辅色：樱粉
    private static final int PANEL_TOP = 0xD8131B29;
    private static final int PANEL_BOTTOM = 0xE60A0F18;
    private static final int BORDER = 0x38FFFFFF;
    private static final int TEXT = 0xFFEAF2FF;
    private static final int MUTED = 0xFF8EA0B5;
    private static final int DIM = 0xFF5E6E80;
    private static final int OK = 0xFF5BE49B;
    private static final int WARN = 0xFFFFC66B;
    private static final int ERR = 0xFFFF6B81;

    // ── 尺寸 ──────────────────────────────────────────────────────────────
    private static final int PAD = 8;
    private static final int HEADER_H = 26;
    private static final int LINE_H = 10;
    private static final int BLOCK_GAP = 6;
    private static final int LABEL_H = 11;
    private static final int PENDING_MAX_LINES = 6;
    private static final int SETTING_ROWS = 9;

    /** 界面里显示的助手名字与徽章文字。 */
    private static final String ASSISTANT_NAME = "香草";
    private static final String BADGE = "香";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    /** 快捷指令：左侧是发给 AI / 本地意图的文字，右侧是界面功能（以 @ 开头）。 */
    private static final String[][] PROMPT_CHIPS = {
            {"全部清除", "全部清除"},
            {"循环清除", "循环清除"},
            {"停止循环清除", "停止循环清除"}
    };
    private static final String[][] TOOL_CHIPS = {
            {"状态", "@status"},
            {"清空记忆", "@memory"},
            {"清屏", "@clear"}
    };

    // ── 跨次打开保留的状态 ────────────────────────────────────────────────
    private static Tab lastTab = Tab.CHAT;
    private static String draft = "";
    private static final List<String> HISTORY = new ArrayList<>();

    private Tab tab = lastTab;

    // 面板与内容区
    private int px, py, pw, ph;
    private int cx, cy, cw, cBottom;

    // ── 对话页 ────────────────────────────────────────────────────────────
    private EditBox promptBox;
    private Rect inputRect;
    private int sendX, sendW, chipsY;
    private int listX, listW, listTop, listBottom;
    private double scroll;
    private boolean followBottom = true;
    private int lastMaxScroll;
    private int lastViewH;
    private int thumbY, thumbH;
    /** 拖动消息内容翻页 / 拖动滚动条滑块。 */
    private boolean draggingList, draggingScrollbar;
    private double dragStartY, dragStartScroll;
    private int contentHeight;
    private long layoutVersion = -1L;
    private int layoutWidth = -1;
    private final List<Block> blocks = new ArrayList<>();
    private int historyIndex = -1;
    private String historyStash = "";
    private boolean suppressResponder;

    // ── 设置页 ────────────────────────────────────────────────────────────
    private EditBox baseUrlBox, apiKeyBox, modelBox, timeoutBox, tokensBox, tempBox;
    private final List<EditBox> settingBoxes = new ArrayList<>();
    private int rowH, labelW, fieldX;
    private Rect rUrl, rKey, rKeyToggle, rModel, rTimeout, rTokens, rTemp;
    private final int[] paramLabelX = new int[3];
    private boolean formEnabled, formStream, formMemory, showKey;
    private HackerClientConfig.EndpointMode formMode = HackerClientConfig.EndpointMode.OPENAI_CHAT_COMPLETIONS;
    private String formBaseUrl = "", formApiKey = "", formModel = "", formTimeout = "", formTokens = "", formTemp = "";
    private String statusText = "";
    private int statusColor = MUTED;
    private boolean testing;

    // ── 交互 ──────────────────────────────────────────────────────────────
    /** 本帧绘制的按钮区域，点击时优先于输入框处理。 */
    private final List<Hit> hits = new ArrayList<>();
    /** 输入框外框区域：点在输入框之外的留白处也能聚焦。 */
    private final List<Hit> fieldHits = new ArrayList<>();
    private String tooltip;
    private String toastText = "";
    private long toastUntil;

    public FangnaiAiScreen() {
        super(Component.literal(ASSISTANT_NAME + "助手"));
        // 打开界面时顺便检查配置文件是否被手动改过
        if (HackerClientConfig.reloadIfChangedOnDisk()) {
            setStatus("检测到配置文件被修改，已自动重新加载。", OK);
        }
        loadForm();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 初始化与布局
    // ══════════════════════════════════════════════════════════════════════

    @Override
    protected void init() {
        computeLayout();
        settingBoxes.clear();

        promptBox = makeBox(inputRect, 2000, draft, value -> {
            draft = value;
            if (!suppressResponder) {
                historyIndex = -1;
            }
        });

        baseUrlBox = settingBox(rUrl, 512, formBaseUrl, value -> formBaseUrl = value);
        apiKeyBox = settingBox(rKey, 512, formApiKey, value -> formApiKey = value);
        // 密钥默认用 * 遮挡，点“显示”切换
        apiKeyBox.setFormatter((text, offset) -> FormattedCharSequence.forward(
                showKey ? text : "*".repeat(text.length()), Style.EMPTY));
        modelBox = settingBox(rModel, 256, formModel, value -> formModel = value);
        timeoutBox = settingBox(rTimeout, 4, formTimeout, value -> formTimeout = value);
        tokensBox = settingBox(rTokens, 6, formTokens, value -> formTokens = value);
        tempBox = settingBox(rTemp, 5, formTemp, value -> formTemp = value);

        applyTabVisibility();
        setInitialFocus(tab == Tab.CHAT ? promptBox : baseUrlBox);
    }

    private void computeLayout() {
        pw = Math.min(width - 8, Mth.clamp(width - 40, 300, 540));
        ph = Math.min(height - 8, Mth.clamp(height - 36, 220, 360));
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        cx = px + PAD;
        cw = pw - PAD * 2;
        cy = py + HEADER_H + 6;
        cBottom = py + ph - PAD;

        // 对话页：自下而上排布输入框、快捷指令、消息列表
        int inputH = 20;
        sendW = 46;
        sendX = cx + cw - sendW;
        inputRect = new Rect(cx, cBottom - inputH, cw - sendW - 5, inputH);
        chipsY = inputRect.y - 5 - 14;
        listX = cx;
        listW = cw;

        // 设置页：固定行数，行高按可用高度自适应
        rowH = Mth.clamp((cBottom - cy) / SETTING_ROWS, 18, 24);
        labelW = 40;
        fieldX = cx + labelW;
        int fw = cx + cw - fieldX;
        rUrl = new Rect(fieldX, rowY(2) + 2, fw, rowH - 4);
        rKey = new Rect(fieldX, rowY(3) + 2, fw - 44, rowH - 4);
        rKeyToggle = new Rect(fieldX + fw - 40, rowY(3) + 3, 40, rowH - 6);
        rModel = new Rect(fieldX, rowY(4) + 2, fw, rowH - 4);
        String[] paramLabels = {"超时秒", "Tokens", "温度"};
        int groupW = (fw - 16) / 3;
        Rect[] params = new Rect[3];
        for (int i = 0; i < 3; i++) {
            int gx = fieldX + i * (groupW + 8);
            int lw = font.width(paramLabels[i]) + 5;
            paramLabelX[i] = gx;
            params[i] = new Rect(gx + lw, rowY(5) + 2, Math.max(20, groupW - lw), rowH - 4);
        }
        rTimeout = params[0];
        rTokens = params[1];
        rTemp = params[2];
    }

    private int rowY(int row) {
        return cy + row * rowH;
    }

    private EditBox makeBox(Rect r, int maxLength, String value, Consumer<String> responder) {
        EditBox box = new EditBox(font, r.x + 6, r.y + (r.h - 8) / 2, r.w - 12, 9, Component.empty());
        box.setBordered(false);
        box.setMaxLength(maxLength);
        box.setTextColor(TEXT);
        box.setValue(value == null ? "" : value);
        box.setResponder(responder);
        return addRenderableWidget(box);
    }

    private EditBox settingBox(Rect r, int maxLength, String value, Consumer<String> responder) {
        EditBox box = makeBox(r, maxLength, value, responder);
        settingBoxes.add(box);
        return box;
    }

    private void applyTabVisibility() {
        boolean chat = tab == Tab.CHAT;
        promptBox.visible = chat;
        for (EditBox box : settingBoxes) {
            box.visible = !chat;
        }
    }

    private void switchTab(Tab target) {
        tab = target;
        lastTab = target;
        applyTabVisibility();
        setFocused(target == Tab.CHAT ? promptBox : baseUrlBox);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 绘制
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        hits.clear();
        fieldHits.clear();
        tooltip = null;

        // 背景压暗 + 面板区域毛玻璃
        g.fillGradient(0, 0, width, height, 0x55000000, 0x99000000);
        g.flush();
        HudBlurRenderer.renderBlurredRegion(minecraft, px, py, pw, ph, 9.0F);
        renderShadow(g);
        roundGradient(g, px, py, pw, ph, PANEL_TOP, PANEL_BOTTOM);
        roundOutline(g, px, py, pw, ph, BORDER);
        g.fill(px + 3, py + 1, px + pw - 3, py + 2, 0x18FFFFFF);

        renderHeader(g, mx, my);
        applyTabVisibility();
        if (tab == Tab.CHAT) {
            renderChat(g, mx, my);
        } else {
            renderSettings(g, mx, my);
        }

        // 输入框控件由父类绘制（它们叠在我们画好的外框上）
        super.render(g, mx, my, partialTick);

        renderToast(g);
        if (tooltip != null && !tooltip.isBlank()) {
            g.renderTooltip(font, wrap(tooltip, 260), mx, my);
        }
    }

    /** 面板外一圈逐渐变淡的阴影。 */
    private void renderShadow(GuiGraphics g) {
        for (int i = 1; i <= 6; i++) {
            int color = ((7 - i) * 0x0A) << 24;
            g.fill(px - i + 2, py - i, px + pw + i - 2, py - i + 1, color);
            g.fill(px - i + 2, py + ph + i - 1, px + pw + i - 2, py + ph + i, color);
            g.fill(px - i, py - i + 2, px - i + 1, py + ph + i - 2, color);
            g.fill(px + pw + i - 1, py - i + 2, px + pw + i, py + ph + i - 2, color);
        }
    }

    private void renderHeader(GuiGraphics g, int mx, int my) {
        // 左侧小徽章
        int bx = px + PAD;
        int by = py + 6;
        roundGradient(g, bx, by, 15, 15, ACCENT, PINK);
        g.drawString(font, BADGE, bx + (15 - font.width(BADGE)) / 2, by + 4, 0xFFFFFFFF, false);

        // 右侧：关闭按钮
        int closeX = px + pw - PAD - 14;
        boolean closeHover = inside(mx, my, closeX, by, 14, 14);
        if (closeHover) {
            roundRect(g, closeX, by, 14, 14, 0x40FF6B81);
        }
        g.drawString(font, "×", closeX + (14 - font.width("×")) / 2, by + 3, closeHover ? ERR : MUTED, false);
        hits.add(new Hit(closeX, by, 14, 14, this::onClose));

        // 标签页切换（分段按钮）
        int segW = 34;
        int tabsX = closeX - 8 - segW * 2;
        roundRect(g, tabsX, by, segW * 2, 14, 0x50000000);
        renderSegment(g, tabsX, by, segW, "对话", Tab.CHAT, mx, my);
        renderSegment(g, tabsX + segW, by, segW, settingsDirty() ? "设置•" : "设置", Tab.SETTINGS, mx, my);

        // 状态胶囊
        StatusPill status = currentStatus();
        int pillW = font.width(status.text) + 17;
        int pillX = tabsX - 8 - pillW;
        roundRect(g, pillX, by + 1, pillW, 12, withAlpha(status.color, 0x24));
        roundOutline(g, pillX, by + 1, pillW, 12, withAlpha(status.color, 0x60));
        float pulse = status.animated ? 0.55F + 0.45F * Mth.sin(Util.getMillis() / 220.0F) : 1.0F;
        g.fill(pillX + 5, by + 5, pillX + 9, by + 9, withAlpha(status.color, (int) (255 * pulse)));
        g.drawString(font, status.text, pillX + 12, by + 3, status.color, false);

        // 标题与副标题（模型 · 主机）
        int titleX = bx + 21;
        int titleMax = pillX - 6 - titleX;
        g.drawString(font, ellipsize(ASSISTANT_NAME + "助手", titleMax), titleX, py + 5, TEXT, false);
        HackerClientConfig.AiSettings ai = HackerClientConfig.ai();
        String sub = (ai.model.isBlank() ? "未设置模型" : ai.model) + " · " + hostOf(AiClientFactory.resolveEndpoint(ai));
        g.pose().pushPose();
        g.pose().translate(titleX, py + 15.5F, 0.0F);
        g.pose().scale(0.75F, 0.75F, 1.0F);
        g.drawString(font, ellipsize(sub, (int) (titleMax / 0.75F)), 0, 0, DIM, false);
        g.pose().popPose();

        // 标题栏下方的青粉渐变分隔线
        int lineY = py + HEADER_H;
        hGradient(g, px + 1, lineY, pw / 2, 1, 0x0000C8FF, 0xCC00C8FF);
        hGradient(g, px + 1 + pw / 2, lineY, pw - 2 - pw / 2, 1, 0xCCFF8FC8, 0x00FF8FC8);
    }

    private void renderSegment(GuiGraphics g, int x, int y, int w, String label, Tab target, int mx, int my) {
        boolean active = tab == target;
        boolean hover = inside(mx, my, x, y, w, 14);
        if (active) {
            roundGradient(g, x + 1, y + 1, w - 2, 12, 0xFF22D3FF, 0xFF0AA4D6);
        } else if (hover) {
            roundRect(g, x + 1, y + 1, w - 2, 12, 0x22FFFFFF);
        }
        int color = active ? 0xFF06121C : (hover ? TEXT : MUTED);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 3, color, false);
        hits.add(new Hit(x, y, w, 14, () -> switchTab(target)));
    }

    private StatusPill currentStatus() {
        AiConversationManager manager = AiConversationManager.get();
        String status = manager.status();
        if (!HackerClientConfig.ai().enabled) {
            return new StatusPill("未启用", DIM, false);
        }
        if ("retrying".equals(status)) {
            return new StatusPill("重试 " + ceilSeconds(manager.retryRemainingMillis()) + "s", WARN, true);
        }
        if (manager.isRequesting()) {
            return new StatusPill("思考中 " + manager.requestElapsedMillis() / 1000L + "s", ACCENT, true);
        }
        if (status.startsWith("workflow:running")) {
            return new StatusPill("执行中", PINK, true);
        }
        if (status.startsWith("workflow")) {
            return new StatusPill("工作流", PINK, true);
        }
        return new StatusPill("就绪", OK, false);
    }

    // ── 对话页 ────────────────────────────────────────────────────────────

    private void renderChat(GuiGraphics g, int mx, int my) {
        List<ActionProposal> pending = PendingActionRegistry.get().snapshot();
        int bottom = chipsY - 5;
        if (pending.size() >= 2) {
            int trayY = chipsY - 5 - 16;
            renderPendingTray(g, mx, my, trayY, pending.size());
            bottom = trayY - 4;
        }
        int top = cy;
        if (!HackerClientConfig.ai().enabled) {
            renderBanner(g, mx, my, top, "AI 尚未启用，点这里前往设置打开", () -> switchTab(Tab.SETTINGS));
            top += 20;
        }
        listTop = top;
        listBottom = bottom;
        relayoutIfNeeded();
        renderMessages(g, mx, my);
        renderChips(g, mx, my);
        renderInput(g, mx, my);
    }

    private void renderBanner(GuiGraphics g, int mx, int my, int y, String text, Runnable action) {
        boolean hover = inside(mx, my, cx, y, cw, 16);
        roundRect(g, cx, y, cw, 16, hover ? 0x40FFC66B : 0x2CFFC66B);
        g.fill(cx, y + 2, cx + 2, y + 14, WARN);
        g.drawString(font, ellipsize(text, cw - 16), cx + 8, y + 4, WARN, false);
        hits.add(new Hit(cx, y, cw, 16, action));
    }

    private void renderPendingTray(GuiGraphics g, int mx, int my, int y, int count) {
        roundRect(g, cx, y, cw, 16, 0x2CFFD166);
        g.fill(cx, y + 2, cx + 2, y + 14, WARN);
        g.drawString(font, "共 " + count + " 个待确认操作", cx + 8, y + 4, WARN, false);
        int denyW = font.width("全部拒绝") + 12;
        int okW = font.width("全部确认") + 12;
        int denyX = cx + cw - 3 - denyW;
        int okX = denyX - 4 - okW;
        pill(g, okX, y + 2, okW, 12, "全部确认", OK, true, mx, my, () -> PendingActionRegistry.get().confirmAll(), null);
        pill(g, denyX, y + 2, denyW, 12, "全部拒绝", ERR, false, mx, my, () -> PendingActionRegistry.get().denyAll(), null);
    }

    /** 聊天记录有变化或宽度变化时重新排版（自动换行、计算每块高度）。 */
    private void relayoutIfNeeded() {
        long version = AiChatLog.get().version();
        if (version == layoutVersion && listW == layoutWidth) {
            return;
        }
        layoutVersion = version;
        layoutWidth = listW;
        blocks.clear();
        int bubbleMax = Math.max(60, (int) (listW * 0.80F) - 14);
        int wideMax = Math.max(60, listW - 6 - 16);
        int total = 0;
        for (AiChatLog.Entry entry : AiChatLog.get().snapshot()) {
            Block block = new Block(entry);
            String text = entry.text();
            switch (entry.kind()) {
                case USER, ASSISTANT -> {
                    block.lines = wrap(text, bubbleMax);
                    block.bubbleW = maxWidth(block.lines) + 14;
                    block.height = LABEL_H + block.lines.size() * LINE_H + 7;
                }
                case WARN, ERROR -> {
                    block.lines = wrap(text, wideMax);
                    block.bubbleW = listW - 6;
                    block.height = block.lines.size() * LINE_H + 7;
                }
                case INFO -> {
                    // 一行能放下就居中显示；多行的系统消息改成左对齐的浅色卡片，方便阅读
                    List<FormattedCharSequence> single = wrap(text, Math.max(60, listW - 40));
                    if (single.size() <= 1) {
                        block.lines = single;
                        block.height = LINE_H - 1;
                    } else {
                        block.boxed = true;
                        block.lines = wrap(text, wideMax);
                        block.bubbleW = listW - 6;
                        block.height = block.lines.size() * LINE_H + 7;
                    }
                }
                case PENDING -> {
                    List<FormattedCharSequence> all = wrap(text, wideMax);
                    block.totalLines = all.size();
                    block.lines = all.size() > PENDING_MAX_LINES ? all.subList(0, PENDING_MAX_LINES) : all;
                    block.bubbleW = listW - 6;
                    int more = all.size() > PENDING_MAX_LINES ? LINE_H : 0;
                    block.height = 4 + LINE_H + block.lines.size() * LINE_H + more + 3 + 14 + 4;
                }
            }
            total += block.height + BLOCK_GAP;
            blocks.add(block);
        }
        contentHeight = Math.max(0, total - BLOCK_GAP);
    }

    private void renderMessages(GuiGraphics g, int mx, int my) {
        int viewH = Math.max(0, listBottom - listTop);
        AiConversationManager manager = AiConversationManager.get();
        boolean thinking = manager.isRequesting() || manager.status().startsWith("workflow");
        int thinkingH = thinking ? LABEL_H + 17 + BLOCK_GAP : 0;
        int total = contentHeight + thinkingH;
        int maxScroll = Math.max(0, total - viewH);
        lastMaxScroll = maxScroll;
        lastViewH = viewH;
        if (followBottom) {
            scroll = maxScroll;
        }
        scroll = Mth.clamp(scroll, 0, maxScroll);

        g.enableScissor(listX, listTop, listX + listW, listBottom);
        if (blocks.isEmpty() && !thinking) {
            renderEmptyState(g, viewH);
        }
        int y = listTop - (int) Math.round(scroll);
        for (Block block : blocks) {
            block.y = y;
            if (y + block.height >= listTop && y <= listBottom) {
                renderBlock(g, block, y, mx, my);
            }
            y += block.height + BLOCK_GAP;
        }
        if (thinking) {
            renderThinking(g, blocks.isEmpty() ? listTop - (int) Math.round(scroll) : y);
        }
        g.disableScissor();

        // 滚动条
        if (maxScroll > 0 && viewH > 0) {
            int trackX = listX + listW - 3;
            thumbH = Math.min(viewH, Math.max(16, viewH * viewH / Math.max(1, total)));
            thumbY = listTop + (int) ((viewH - thumbH) * (scroll / maxScroll));
            boolean barHover = mx >= listX + listW - 8 && mx <= listX + listW && my >= listTop && my <= listBottom;
            boolean active = draggingScrollbar || draggingList || barHover;
            g.fill(trackX, listTop, trackX + 2, listBottom, active ? 0x22FFFFFF : 0x14FFFFFF);
            g.fill(trackX - (active ? 1 : 0), thumbY, trackX + 2, thumbY + thumbH, active ? 0xC000C8FF : 0x66FFFFFF);
        }
        // 翻到上面时显示“回到最新”
        if (!followBottom && maxScroll - scroll > 24) {
            String label = "↓ 最新消息";
            int w = font.width(label) + 14;
            pill(g, listX + (listW - w) / 2, listBottom - 18, w, 14, label, ACCENT, true, mx, my, () -> followBottom = true, null);
        }
    }

    private void renderEmptyState(GuiGraphics g, int viewH) {
        int midX = listX + listW / 2;
        int top = listTop + Math.max(4, viewH / 2 - 34);
        roundGradient(g, midX - 12, top, 24, 24, ACCENT, PINK);
        g.drawString(font, BADGE, midX - font.width(BADGE) / 2, top + 8, 0xFFFFFFFF, false);
        drawCentered(g, "嗨～我是" + ASSISTANT_NAME + "，有什么想让我做的吗？", midX, top + 32, TEXT);
        drawCentered(g, "在下方输入后按 Enter 发送，也可以点快捷指令", midX, top + 45, MUTED);
        drawCentered(g, "↑/↓ 翻历史 · 右键消息复制 · ESC 关闭", midX, top + 57, DIM);
    }

    private void renderBlock(GuiGraphics g, Block block, int y, int mx, int my) {
        AiChatLog.Entry entry = block.entry;
        int right = listX + listW - 6;
        switch (entry.kind()) {
            case USER -> {
                String label = time(entry) + "  你";
                g.drawString(font, label, right - font.width(label), y, DIM, false);
                int bx = right - block.bubbleW;
                int by = y + LABEL_H;
                roundGradient(g, bx, by, block.bubbleW, block.height - LABEL_H, 0xE62E8BD6, 0xE61D63A3);
                drawLines(g, block.lines, bx + 7, by + 4, 0xFFFFFFFF);
            }
            case ASSISTANT -> {
                g.drawString(font, ASSISTANT_NAME, listX + 1, y, PINK, false);
                g.drawString(font, time(entry), listX + 6 + font.width(ASSISTANT_NAME), y, DIM, false);
                int by = y + LABEL_H;
                int bh = block.height - LABEL_H;
                roundRect(g, listX, by, block.bubbleW, bh, 0xD81B2434);
                g.fill(listX, by + 2, listX + 2, by + bh - 2, PINK);
                drawLines(g, block.lines, listX + 8, by + 4, TEXT);
            }
            case INFO -> {
                if (block.boxed) {
                    roundRect(g, listX, y, block.bubbleW, block.height, 0x14FFFFFF);
                    g.fill(listX, y + 2, listX + 2, y + block.height - 2, 0x4000C8FF);
                    drawLines(g, block.lines, listX + 8, y + 4, MUTED);
                } else {
                    for (FormattedCharSequence line : block.lines) {
                        g.drawString(font, line, listX + (listW - 6 - font.width(line)) / 2, y, MUTED, false);
                    }
                }
            }
            case WARN, ERROR -> {
                boolean error = entry.kind() == AiChatLog.Kind.ERROR;
                roundRect(g, listX, y, block.bubbleW, block.height, error ? 0x40FF4D6A : 0x30FFB347);
                g.fill(listX, y + 2, listX + 2, y + block.height - 2, error ? ERR : WARN);
                drawLines(g, block.lines, listX + 8, y + 4, error ? 0xFFFFD3DA : 0xFFFFE4B5);
            }
            case PENDING -> renderPendingBlock(g, block, y, mx, my);
        }
    }

    private void renderPendingBlock(GuiGraphics g, Block block, int y, int mx, int my) {
        AiChatLog.Entry entry = block.entry;
        boolean active = PendingActionRegistry.get().isPending(entry.pendingId());
        roundRect(g, listX, y, block.bubbleW, block.height, active ? 0x30FFD166 : 0x16FFFFFF);
        g.fill(listX, y + 2, listX + 2, y + block.height - 2, active ? WARN : DIM);
        g.drawString(font, "需要确认", listX + 8, y + 4, active ? WARN : DIM, false);
        String id = "#" + entry.pendingId();
        g.drawString(font, id, listX + block.bubbleW - 6 - font.width(id), y + 4, DIM, false);
        int ly = y + 4 + LINE_H;
        drawLines(g, block.lines, listX + 8, ly, active ? TEXT : MUTED);
        ly += block.lines.size() * LINE_H;
        if (block.totalLines > block.lines.size()) {
            g.drawString(font, "… 共 " + block.totalLines + " 行，右键复制全文", listX + 8, ly, DIM, false);
        }
        int btnY = y + block.height - 4 - 14;
        if (active) {
            boolean clickable = btnY >= listTop && btnY + 14 <= listBottom;
            int denyW = 40;
            int denyX = listX + block.bubbleW - 6 - denyW;
            int okX = denyX - 4 - denyW;
            String pendingId = entry.pendingId();
            pill(g, okX, btnY, denyW, 14, "确认", OK, true, mx, my,
                    clickable ? () -> PendingActionRegistry.get().confirm(pendingId) : null, null);
            pill(g, denyX, btnY, denyW, 14, "拒绝", ERR, false, mx, my,
                    clickable ? () -> PendingActionRegistry.get().deny(pendingId) : null, null);
        } else {
            g.drawString(font, "已处理或已过期", listX + 8, btnY + 3, DIM, false);
        }
    }

    /** 请求进行中时显示在列表末尾的“思考中”气泡。 */
    private void renderThinking(GuiGraphics g, int y) {
        AiConversationManager manager = AiConversationManager.get();
        String text;
        int color;
        if ("retrying".equals(manager.status())) {
            text = "请求出错，" + ceilSeconds(manager.retryRemainingMillis()) + " 秒后自动重试";
            color = WARN;
        } else if (manager.isRequesting()) {
            text = "思考中" + ".".repeat((int) (Util.getMillis() / 350L % 4L)) + "  " + manager.requestElapsedMillis() / 1000L + "s";
            color = TEXT;
        } else {
            text = "工作流处理中" + ".".repeat((int) (Util.getMillis() / 350L % 4L));
            color = TEXT;
        }
        g.drawString(font, ASSISTANT_NAME, listX + 1, y, PINK, false);
        int by = y + LABEL_H;
        int w = Math.max(80, font.width(text) + 16);
        roundRect(g, listX, by, w, 17, 0xD81B2434);
        g.fill(listX, by + 2, listX + 2, by + 15, PINK);
        g.drawString(font, text, listX + 8, by + 4, color, false);
    }

    private void renderChips(GuiGraphics g, int mx, int my) {
        int h = 14;
        // 右侧：界面功能
        int rx = cx + cw;
        int rightLimit = rx;
        for (int i = TOOL_CHIPS.length - 1; i >= 0; i--) {
            String label = TOOL_CHIPS[i][0];
            String action = TOOL_CHIPS[i][1];
            int w = font.width(label) + 12;
            rx -= w;
            pill(g, rx, chipsY, w, h, label, MUTED, false, mx, my, () -> runChip(action), null);
            rx -= 4;
            rightLimit = rx;
        }
        // 左侧：快捷发送，放不下就不画
        int lx = cx;
        for (String[] chip : PROMPT_CHIPS) {
            int w = font.width(chip[0]) + 12;
            if (lx + w > rightLimit - 6) {
                break;
            }
            String prompt = chip[1];
            pill(g, lx, chipsY, w, h, chip[0], ACCENT, false, mx, my, () -> runChip(prompt), "直接发送：" + prompt);
            lx += w + 4;
        }
    }

    private void renderInput(GuiGraphics g, int mx, int my) {
        Rect r = inputRect;
        boolean focused = promptBox.isFocused();
        roundRect(g, r.x, r.y, r.w, r.h, focused ? 0x88000000 : 0x66000000);
        roundOutline(g, r.x, r.y, r.w, r.h, focused ? 0xC000C8FF : 0x30FFFFFF);
        if (promptBox.getValue().isEmpty()) {
            g.drawString(font, ellipsize("和香草说点什么…（Enter 发送）", r.w - 14), promptBox.getX(), promptBox.getY(), DIM, false);
        }
        fieldHits.add(new Hit(r.x, r.y, r.w, r.h, () -> setFocused(promptBox)));

        boolean busy = AiConversationManager.get().isRequesting();
        int sy = r.y;
        boolean hover = inside(mx, my, sendX, sy, sendW, r.h);
        if (busy) {
            roundGradient(g, sendX, sy, sendW, r.h, hover ? 0xFFFF8597 : 0xFFFF6B81, 0xFFD94A62);
            drawCentered(g, "停止", sendX + sendW / 2, sy + 6, 0xFFFFFFFF);
            hits.add(new Hit(sendX, sy, sendW, r.h, () -> AiConversationManager.get().cancel()));
            if (hover) {
                tooltip = "取消当前请求和等待中的自动重试";
            }
        } else {
            boolean empty = promptBox.getValue().isBlank();
            int top = empty ? 0xFF2A4A5C : (hover ? 0xFF4FDBFF : 0xFF22D3FF);
            int bottom = empty ? 0xFF203848 : 0xFF0A9FD0;
            roundGradient(g, sendX, sy, sendW, r.h, top, bottom);
            drawCentered(g, "发送", sendX + sendW / 2, sy + 6, empty ? MUTED : 0xFF06121C);
            hits.add(new Hit(sendX, sy, sendW, r.h, this::sendPrompt));
        }
    }

    // ── 设置页 ────────────────────────────────────────────────────────────

    private void renderSettings(GuiGraphics g, int mx, int my) {
        int fw = cx + cw - fieldX;

        // 行 0：开关
        rowLabel(g, "开关", 0);
        int tx = fieldX;
        tx = toggle(g, tx, rowY(0), "启用 AI", formEnabled, () -> formEnabled = !formEnabled);
        tx = toggle(g, tx + 14, rowY(0), "流式输出", formStream, () -> formStream = !formStream);
        toggle(g, tx + 14, rowY(0), "上下文记忆", formMemory, () -> formMemory = !formMemory);

        // 行 1：接口类型（左键下一个，右键上一个）
        rowLabel(g, "接口", 1);
        String mode = "‹ " + modeLabel(formMode) + " ›";
        int modeW = font.width(mode) + 14;
        Rect modeRect = new Rect(fieldX, rowY(1) + 2, modeW, rowH - 4);
        boolean modeHover = modeRect.contains(mx, my);
        roundRect(g, modeRect.x, modeRect.y, modeRect.w, modeRect.h, modeHover ? 0x3300C8FF : 0x22000000);
        roundOutline(g, modeRect.x, modeRect.y, modeRect.w, modeRect.h, modeHover ? 0xA000C8FF : 0x40FFFFFF);
        g.drawString(font, mode, modeRect.x + 7, modeRect.y + (modeRect.h - 8) / 2, ACCENT, false);
        hits.add(new Hit(modeRect.x, modeRect.y, modeRect.w, modeRect.h, () -> cycleMode(1), () -> cycleMode(-1)));
        if (modeHover) {
            tooltip = "左键切换下一种，右键切换上一种。大多数中转/兼容接口选 Chat Completions。";
        }

        // 行 2~4：文本输入
        rowLabel(g, "地址", 2);
        fieldFrame(g, rUrl, baseUrlBox, "https://api.example.com/v1", mx, my);
        rowLabel(g, "密钥", 3);
        fieldFrame(g, rKey, apiKeyBox, "sk-...", mx, my);
        pill(g, rKeyToggle.x, rKeyToggle.y, rKeyToggle.w, rKeyToggle.h, showKey ? "隐藏" : "显示", MUTED, false, mx, my,
                () -> showKey = !showKey, null);
        rowLabel(g, "模型", 4);
        fieldFrame(g, rModel, modelBox, "模型名称", mx, my);

        // 行 5：数值参数
        rowLabel(g, "参数", 5);
        String[] paramLabels = {"超时秒", "Tokens", "温度"};
        for (int i = 0; i < 3; i++) {
            g.drawString(font, paramLabels[i], paramLabelX[i], rowY(5) + (rowH - 8) / 2, MUTED, false);
        }
        fieldFrame(g, rTimeout, timeoutBox, "180", mx, my);
        fieldFrame(g, rTokens, tokensBox, "8192", mx, my);
        fieldFrame(g, rTemp, tempBox, "0.2", mx, my);

        // 行 6：最终请求地址预览
        rowLabel(g, "请求", 6);
        String url = AiClientFactory.resolveEndpoint(formBaseUrl, formMode);
        String warning = addressWarning(formBaseUrl);
        String path = AiClientFactory.endpointPath(formMode);
        boolean autoV1 = url.endsWith("/v1" + path) && !trimSlash(formBaseUrl).endsWith("/v1");
        String shown = (warning == null ? "" : "⚠ ") + url;
        int textY = rowY(6) + (rowH - 8) / 2;
        String note = autoV1 ? "  (已自动补 /v1)" : "";
        int noteW = font.width(note);
        String clipped = ellipsize(shown, fw - noteW);
        g.drawString(font, clipped, fieldX, textY, warning == null ? ACCENT : WARN, false);
        if (!note.isEmpty()) {
            g.drawString(font, note, fieldX + font.width(clipped), textY, DIM, false);
        }
        if (inside(mx, my, fieldX, rowY(6), fw, rowH)) {
            tooltip = "实际请求地址：" + url + (warning == null ? "" : "\n" + warning)
                    + "\n规则：只填域名时自动补 /v1；粘贴完整接口地址时原样使用。";
        }

        // 行 7：操作按钮
        int by = rowY(7) + 2;
        int bh = rowH - 4;
        int bx = fieldX;
        int w1 = font.width("保存设置") + 18;
        pill(g, bx, by, w1, bh, "保存设置", ACCENT, true, mx, my, this::saveSettings, "保存到配置文件，立即生效");
        bx += w1 + 6;
        String testLabel = testing ? "测试中…" : "测试连接";
        int w2 = font.width(testLabel) + 18;
        pill(g, bx, by, w2, bh, testLabel, ACCENT, false, mx, my, testing ? null : this::testConnection,
                "用当前填写的内容发一条很短的测试请求（不会写入对话记忆）");
        bx += w2 + 6;
        int w3 = font.width("从文件重载") + 18;
        pill(g, bx, by, w3, bh, "从文件重载", MUTED, false, mx, my, this::reloadFromFile,
                "放弃界面里未保存的修改，重新读取 " + HackerClientConfig.path());

        // 行 8：状态信息
        String message = statusText;
        int color = statusColor;
        if (message.isBlank() && settingsDirty()) {
            message = "有未保存的修改（关闭界面时也会自动保存）";
            color = WARN;
        }
        if (!message.isBlank()) {
            List<FormattedCharSequence> lines = wrap(message, cw);
            int sy = rowY(8) + 3;
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                g.drawString(font, lines.get(i), cx, sy + i * LINE_H, color, false);
            }
            if (lines.size() > 2 && inside(mx, my, cx, sy, cw, LINE_H * 2)) {
                tooltip = message;
            }
        }
    }

    private void rowLabel(GuiGraphics g, String label, int row) {
        g.drawString(font, label, cx, rowY(row) + (rowH - 8) / 2, MUTED, false);
    }

    /** 绘制开关：小胶囊 + 文字，返回右边界。 */
    private int toggle(GuiGraphics g, int x, int rowTop, String label, boolean on, Runnable flip) {
        int mid = rowTop + rowH / 2;
        int sw = 20;
        int sh = 10;
        int sy = mid - sh / 2;
        roundRect(g, x, sy, sw, sh, on ? 0xE000C8FF : 0x40FFFFFF);
        int knobX = on ? x + sw - 9 : x + 1;
        roundRect(g, knobX, sy + 1, 8, 8, on ? 0xFFFFFFFF : 0xFFB8C4D0);
        int lx = x + sw + 5;
        g.drawString(font, label, lx, mid - 4, on ? TEXT : MUTED, false);
        int end = lx + font.width(label);
        hits.add(new Hit(x, rowTop + 2, end - x, rowH - 4, flip));
        return end;
    }

    /** 输入框外框：聚焦时高亮青色描边，空值时显示占位文字。 */
    private void fieldFrame(GuiGraphics g, Rect r, EditBox box, String placeholder, int mx, int my) {
        boolean focused = box.isFocused();
        boolean hover = r.contains(mx, my);
        roundRect(g, r.x, r.y, r.w, r.h, focused ? 0x88000000 : (hover ? 0x70000000 : 0x5A000000));
        roundOutline(g, r.x, r.y, r.w, r.h, focused ? 0xC000C8FF : (hover ? 0x50FFFFFF : 0x28FFFFFF));
        if (box.getValue().isEmpty() && placeholder != null) {
            g.drawString(font, ellipsize(placeholder, r.w - 12), box.getX(), box.getY(), DIM, false);
        }
        fieldHits.add(new Hit(r.x, r.y, r.w, r.h, () -> setFocused(box)));
    }

    private void renderToast(GuiGraphics g) {
        if (toastText.isBlank() || Util.getMillis() > toastUntil) {
            return;
        }
        int w = font.width(toastText) + 18;
        int x = px + (pw - w) / 2;
        int y = cy + 2;
        roundRect(g, x, y, w, 16, 0xF0202B3C);
        roundOutline(g, x, y, w, 16, 0x8000C8FF);
        g.drawString(font, toastText, x + 9, y + 4, TEXT, false);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 交互
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 先处理按钮（倒序：后画的在上层）
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(mx, my)) {
                Runnable action = button == 1 ? hit.rightAction : hit.action;
                if (action != null) {
                    action.run();
                    return true;
                }
            }
        }
        // 对话页左键按住消息区：开始拖动翻页；按在右侧滚动条上：拖动滑块
        if (button == 0 && tab == Tab.CHAT && inList(mx, my)) {
            if (lastMaxScroll > 0 && mx >= listX + listW - 8) {
                draggingScrollbar = true;
                // 点在滑块外面时，先让滑块中心跳到鼠标位置
                if (my < thumbY || my > thumbY + thumbH) {
                    scrollToThumbTop(my - thumbH / 2.0D);
                }
            } else {
                draggingList = true;
            }
            dragStartY = my;
            dragStartScroll = scroll;
            followBottom = false;
            return true;
        }
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        for (Hit hit : fieldHits) {
            if (hit.contains(mx, my) && hit.action != null) {
                hit.action.run();
                return true;
            }
        }
        // 对话页右键复制消息
        if (button == 1 && tab == Tab.CHAT && my >= listTop && my <= listBottom && mx >= listX && mx <= listX + listW) {
            for (Block block : blocks) {
                if (my >= block.y && my < block.y + block.height) {
                    minecraft.keyboardHandler.setClipboard(block.entry.text());
                    toast("已复制到剪贴板");
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (draggingScrollbar) {
            // 滑块移动距离按比例换算成内容滚动距离
            double track = Math.max(1, lastViewH - thumbH);
            setScroll(dragStartScroll + (my - dragStartY) * lastMaxScroll / track);
            return true;
        }
        if (draggingList) {
            // 往上拖看下面的消息，往下拖看上面的消息（跟手机上滑动一致）
            setScroll(dragStartScroll - (my - dragStartY));
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (draggingList || draggingScrollbar) {
            draggingList = false;
            draggingScrollbar = false;
            followBottom = scroll >= lastMaxScroll - 1;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    private void setScroll(double value) {
        scroll = Mth.clamp(value, 0.0D, lastMaxScroll);
        followBottom = false;
    }

    private void scrollToThumbTop(double thumbTop) {
        double track = Math.max(1, lastViewH - thumbH);
        setScroll((thumbTop - listTop) / track * lastMaxScroll);
    }

    private boolean inList(double mx, double my) {
        return mx >= listX && mx <= listX + listW && my >= listTop && my <= listBottom;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (tab == Tab.CHAT && mx >= listX && mx <= listX + listW && my >= listTop && my <= listBottom) {
            scroll = Mth.clamp(scroll - delta * 24.0D, 0.0D, lastMaxScroll);
            followBottom = scroll >= lastMaxScroll - 1;
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        boolean enter = key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER;
        if (tab == Tab.CHAT && getFocused() == promptBox) {
            if (enter) {
                sendPrompt();
                return true;
            }
            if (key == InputConstants.KEY_UP) {
                historyPrev();
                return true;
            }
            if (key == InputConstants.KEY_DOWN) {
                historyNext();
                return true;
            }
        }
        if (tab == Tab.SETTINGS && enter && getFocused() instanceof EditBox) {
            saveSettings();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public void tick() {
        // 驱动输入框光标闪烁
        promptBox.tick();
        for (EditBox box : settingBoxes) {
            box.tick();
        }
    }

    @Override
    public void onClose() {
        lastTab = tab;
        if (settingsDirty()) {
            applyAndSave();
            ChatFeedback.info("已自动保存 AI 设置。实际请求地址：" + AiClientFactory.resolveEndpoint(HackerClientConfig.ai()));
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ── 对话操作 ──────────────────────────────────────────────────────────

    private void sendPrompt() {
        String text = promptBox.getValue().trim();
        if (text.isEmpty()) {
            return;
        }
        // 请求进行中时不允许再发，否则会打断正在运行的工作流
        if (AiConversationManager.get().isRequesting()) {
            toast("上一条还在处理中，可以先点“停止”");
            return;
        }
        if (HISTORY.isEmpty() || !HISTORY.get(HISTORY.size() - 1).equals(text)) {
            HISTORY.add(text);
            while (HISTORY.size() > 50) {
                HISTORY.remove(0);
            }
        }
        historyIndex = -1;
        setPromptSilently("");
        followBottom = true;
        if (text.startsWith("/")) {
            // 以 / 开头当作指令：本模组指令直接处理，其他交给游戏
            if (!FangnaiHackerCommands.dispatch(text) && minecraft.player != null) {
                minecraft.player.connection.sendCommand(text.substring(1));
            }
            return;
        }
        FangnaiHackerCommands.submitPrompt(text);
    }

    private void runChip(String action) {
        followBottom = true;
        switch (action) {
            case "@status" -> FangnaiHackerCommands.dispatch("fangnai hacker status");
            case "@memory" -> FangnaiHackerCommands.dispatch("fangnai hacker memory clear");
            case "@clear" -> {
                AiChatLog.get().clear();
                toast("已清空界面消息（不影响 AI 记忆）");
            }
            default -> {
                if (AiConversationManager.get().isRequesting()) {
                    toast("上一条还在处理中，可以先点“停止”");
                    return;
                }
                FangnaiHackerCommands.submitPrompt(action);
            }
        }
    }

    private void historyPrev() {
        if (HISTORY.isEmpty()) {
            return;
        }
        if (historyIndex < 0) {
            historyStash = promptBox.getValue();
            historyIndex = HISTORY.size();
        }
        if (historyIndex > 0) {
            historyIndex--;
        }
        setPromptSilently(HISTORY.get(historyIndex));
    }

    private void historyNext() {
        if (historyIndex < 0) {
            return;
        }
        historyIndex++;
        if (historyIndex >= HISTORY.size()) {
            historyIndex = -1;
            setPromptSilently(historyStash);
        } else {
            setPromptSilently(HISTORY.get(historyIndex));
        }
    }

    private void setPromptSilently(String value) {
        suppressResponder = true;
        promptBox.setValue(value);
        promptBox.moveCursorToEnd();
        suppressResponder = false;
        draft = value;
    }

    // ── 设置操作 ──────────────────────────────────────────────────────────

    private void loadForm() {
        HackerClientConfig.AiSettings ai = HackerClientConfig.ai();
        formEnabled = ai.enabled;
        formStream = ai.stream;
        formMemory = ai.contextMemoryEnabled;
        formMode = ai.endpointMode;
        formBaseUrl = ai.baseUrl;
        formApiKey = ai.apiKey;
        formModel = ai.model;
        formTimeout = Integer.toString(ai.timeoutSeconds);
        formTokens = Integer.toString(ai.maxTokens);
        formTemp = trimNumber(ai.temperature);
    }

    /** 把表单内容同步回输入框（保存/重载后数值可能被规范化）。 */
    private void syncBoxesFromForm() {
        setBoxSilently(baseUrlBox, formBaseUrl);
        setBoxSilently(apiKeyBox, formApiKey);
        setBoxSilently(modelBox, formModel);
        setBoxSilently(timeoutBox, formTimeout);
        setBoxSilently(tokensBox, formTokens);
        setBoxSilently(tempBox, formTemp);
    }

    private static void setBoxSilently(EditBox box, String value) {
        if (box != null && !box.getValue().equals(value)) {
            box.setValue(value);
        }
    }

    private void applyForm(HackerClientConfig.AiSettings target) {
        target.enabled = formEnabled;
        target.stream = formStream;
        target.contextMemoryEnabled = formMemory;
        target.endpointMode = formMode;
        target.baseUrl = formBaseUrl.trim();
        target.apiKey = formApiKey.trim();
        target.model = formModel.trim();
        target.timeoutSeconds = parseInt(formTimeout, target.timeoutSeconds);
        target.maxTokens = parseInt(formTokens, target.maxTokens);
        target.temperature = parseDouble(formTemp, target.temperature);
        target.clamp();
    }

    private void applyAndSave() {
        // 先合并磁盘上的外部修改，再写入界面里的值，避免覆盖掉玩家手改的其他字段
        HackerClientConfig.reloadIfChangedOnDisk();
        applyForm(HackerClientConfig.ai());
        HackerClientConfig.save();
    }

    private void saveSettings() {
        applyAndSave();
        loadForm();
        syncBoxesFromForm();
        setStatus("已保存 ✓  实际请求地址：" + AiClientFactory.resolveEndpoint(HackerClientConfig.ai()), OK);
    }

    private void reloadFromFile() {
        HackerClientConfig.load();
        loadForm();
        syncBoxesFromForm();
        setStatus("已从文件重新加载：" + HackerClientConfig.path(), OK);
    }

    private boolean settingsDirty() {
        HackerClientConfig.AiSettings ai = HackerClientConfig.ai();
        return formEnabled != ai.enabled
                || formStream != ai.stream
                || formMemory != ai.contextMemoryEnabled
                || formMode != ai.endpointMode
                || !formBaseUrl.trim().equals(ai.baseUrl)
                || !formApiKey.trim().equals(ai.apiKey)
                || !formModel.trim().equals(ai.model)
                || parseInt(formTimeout, ai.timeoutSeconds) != ai.timeoutSeconds
                || parseInt(formTokens, ai.maxTokens) != ai.maxTokens
                || Math.abs(parseDouble(formTemp, ai.temperature) - ai.temperature) > 1.0E-9D;
    }

    private void cycleMode(int direction) {
        HackerClientConfig.EndpointMode[] modes = HackerClientConfig.EndpointMode.values();
        int index = (formMode.ordinal() + direction + modes.length) % modes.length;
        formMode = modes[index];
    }

    /** 用表单里的值（不必先保存）发一条很短的请求，验证地址、密钥和模型是否可用。 */
    private void testConnection() {
        HackerClientConfig.AiSettings copy = HackerClientConfig.gson().fromJson(
                HackerClientConfig.gson().toJson(HackerClientConfig.ai()), HackerClientConfig.AiSettings.class);
        applyForm(copy);
        copy.maxTokens = Math.min(copy.maxTokens, 256);
        String url = AiClientFactory.resolveEndpoint(copy);
        testing = true;
        setStatus("正在测试 " + url + " …", MUTED);
        long start = System.currentTimeMillis();
        List<AiMessage> messages = List.of(
                new AiMessage("system", "这是连通性测试，请只回复 OK。"),
                new AiMessage("user", "ping"));
        try {
            AiClientFactory.create(copy).send(new AiRequest(messages)).whenComplete((response, throwable) ->
                    Minecraft.getInstance().execute(() -> {
                        testing = false;
                        long ms = System.currentTimeMillis() - start;
                        if (throwable != null) {
                            Throwable root = unwrap(throwable);
                            if (root instanceof AiHttpException http) {
                                setStatus("✗ 连接失败（HTTP " + http.statusCode() + "）：" + http.hint(), ERR);
                            } else {
                                setStatus("✗ 连接失败：" + root.getClass().getSimpleName()
                                        + (root.getMessage() == null ? "" : "：" + root.getMessage()), ERR);
                            }
                            return;
                        }
                        String reply = response.content().isBlank()
                                ? (response.toolCalls().isEmpty() ? "（返回内容为空，但接口可用）" : "（返回了工具调用）")
                                : "回复：" + response.content().strip();
                        setStatus("✓ 连接成功，用时 " + ms + " ms。" + reply, OK);
                    }));
        } catch (Exception e) {
            testing = false;
            setStatus("✗ 无法发起请求：" + e.getClass().getSimpleName() + "：" + e.getMessage(), ERR);
        }
    }

    private void setStatus(String text, int color) {
        statusText = text == null ? "" : text;
        statusColor = color;
    }

    private void toast(String text) {
        toastText = text;
        toastUntil = Util.getMillis() + 1800L;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 绘制小工具
    // ══════════════════════════════════════════════════════════════════════

    /** 胶囊按钮：filled 为实心，否则为描边；action 为 null 时只画不响应。 */
    private void pill(GuiGraphics g, int x, int y, int w, int h, String label, int color, boolean filled,
                      int mx, int my, Runnable action, String tip) {
        boolean hover = action != null && inside(mx, my, x, y, w, h);
        if (filled) {
            roundRect(g, x, y, w, h, withAlpha(color, hover ? 0xFF : 0xD8));
        } else {
            roundRect(g, x, y, w, h, withAlpha(color, hover ? 0x40 : 0x1C));
            roundOutline(g, x, y, w, h, withAlpha(color, hover ? 0xC0 : 0x66));
        }
        int textColor = filled ? 0xFF06121C : (hover ? 0xFFFFFFFF : color);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, textColor, false);
        if (action != null) {
            hits.add(new Hit(x, y, w, h, action));
        }
        if (tip != null && inside(mx, my, x, y, w, h)) {
            tooltip = tip;
        }
    }

    private void drawLines(GuiGraphics g, List<FormattedCharSequence> lines, int x, int y, int color) {
        int ly = y;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, x, ly, color, false);
            ly += LINE_H;
        }
    }

    private void drawCentered(GuiGraphics g, String text, int centerX, int y, int color) {
        g.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    /**
     * 中英文混排换行：连续的英文/数字/符号视为一个单词尽量不拆开，
     * 中文可以在任意两个字之间断行，中文标点不会被挤到行首，超长网址按字符硬切。
     * （原版换行只在空格处断开，会把整句中文当成一个超长单词，排版很难看。）
     */
    private List<FormattedCharSequence> wrap(String text, int maxWidth) {
        List<FormattedCharSequence> result = new ArrayList<>();
        for (String line : wrapPlain(text, Math.max(10, maxWidth))) {
            result.add(FormattedCharSequence.forward(line, Style.EMPTY));
        }
        return result;
    }

    private List<String> wrapPlain(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        String source = text == null ? "" : text.replace("\r", "").replace("\t", "    ");
        for (String paragraph : source.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            int lineWidth = 0;
            boolean wrapped = false;
            int i = 0;
            while (i < paragraph.length()) {
                int end = tokenEnd(paragraph, i);
                String token = paragraph.substring(i, end);
                i = end;
                boolean space = token.isBlank();
                // 自动换行后的新行不以空格开头
                if (space && wrapped && line.length() == 0) {
                    continue;
                }
                int tokenWidth = font.width(token);
                if (lineWidth + tokenWidth > maxWidth && line.length() > 0 && !isClosingPunct(token)) {
                    lines.add(line.toString().stripTrailing());
                    line.setLength(0);
                    lineWidth = 0;
                    wrapped = true;
                    if (space) {
                        continue;
                    }
                }
                if (tokenWidth > maxWidth && !space) {
                    // 单个“单词”比整行还宽（比如长网址）：逐字符硬切
                    int k = 0;
                    while (k < token.length()) {
                        int next = k + Character.charCount(token.codePointAt(k));
                        String piece = token.substring(k, next);
                        int pieceWidth = font.width(piece);
                        if (lineWidth + pieceWidth > maxWidth && line.length() > 0) {
                            lines.add(line.toString());
                            line.setLength(0);
                            lineWidth = 0;
                            wrapped = true;
                        }
                        line.append(piece);
                        lineWidth += pieceWidth;
                        k = next;
                    }
                    continue;
                }
                line.append(token);
                lineWidth += tokenWidth;
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /** 切出下一个排版单元：英文单词 / 连续空格 / 单个其他字符（中文、全角标点等）。 */
    private static int tokenEnd(String text, int start) {
        int codePoint = text.codePointAt(start);
        int end = start + Character.charCount(codePoint);
        if (isAsciiWord(codePoint)) {
            while (end < text.length() && isAsciiWord(text.codePointAt(end))) {
                end += Character.charCount(text.codePointAt(end));
            }
        } else if (codePoint == ' ') {
            while (end < text.length() && text.charAt(end) == ' ') {
                end++;
            }
        }
        return end;
    }

    private static boolean isAsciiWord(int codePoint) {
        return codePoint > 32 && codePoint < 127;
    }

    /** 不能出现在行首的中文标点：允许它稍微超出行宽，跟在上一个字后面。 */
    private static boolean isClosingPunct(String token) {
        return token.length() == 1 && "，。、；：！？）》」』】…".indexOf(token.charAt(0)) >= 0;
    }

    private int maxWidth(List<FormattedCharSequence> lines) {
        int max = 0;
        for (FormattedCharSequence line : lines) {
            max = Math.max(max, font.width(line));
        }
        return max;
    }

    private String ellipsize(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    /** 2 像素圆角矩形（逐行填充，避免半透明颜色在重叠处叠加变深）。 */
    private static void roundRect(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (w <= 4 || h <= 4) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x + 2, y, x + w - 2, y + 1, color);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, color);
        g.fill(x, y + 2, x + w, y + h - 2, color);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, color);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, color);
    }

    /** 竖直渐变的圆角矩形。 */
    private static void roundGradient(GuiGraphics g, int x, int y, int w, int h, int top, int bottom) {
        if (w <= 4 || h <= 4) {
            g.fillGradient(x, y, x + w, y + h, top, bottom);
            return;
        }
        g.fill(x + 2, y, x + w - 2, y + 1, top);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, top);
        g.fillGradient(x, y + 2, x + w, y + h - 2, top, bottom);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, bottom);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, bottom);
    }

    /** 1 像素圆角描边。 */
    private static void roundOutline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 2, y, x + w - 2, y + 1, color);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, color);
        g.fill(x, y + 2, x + 1, y + h - 2, color);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, color);
        g.fill(x + 1, y + 1, x + 2, y + 2, color);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, color);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, color);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, color);
    }

    /** 水平渐变（按 2 像素一段逐段填色）。 */
    private static void hGradient(GuiGraphics g, int x, int y, int w, int h, int left, int right) {
        if (w <= 0) {
            return;
        }
        int steps = Math.max(1, w / 2);
        for (int i = 0; i < steps; i++) {
            int x0 = x + i * w / steps;
            int x1 = x + (i + 1) * w / steps;
            g.fill(x0, y, x1, y + h, lerpColor(left, right, (i + 0.5F) / steps));
        }
    }

    private static int lerpColor(int a, int b, float t) {
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int oa = (int) (aa + (ba - aa) * t);
        int or = (int) (ar + (br - ar) * t);
        int og = (int) (ag + (bg - ag) * t);
        int ob = (int) (ab + (bb - ab) * t);
        return (oa << 24) | (or << 16) | (og << 8) | ob;
    }

    private static int withAlpha(int color, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0xFFFFFF);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 文本小工具
    // ══════════════════════════════════════════════════════════════════════

    private static String modeLabel(HackerClientConfig.EndpointMode mode) {
        return switch (mode) {
            case OPENAI_CHAT_COMPLETIONS -> "Chat Completions";
            case OPENAI_COMPLETIONS -> "Completions（旧版）";
            case MESSAGES -> "Messages";
        };
    }

    /** 对明显填错的 Base URL 给出提示（只做通用判断）。 */
    private static String addressWarning(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.isEmpty()) {
            return "Base URL 为空，将使用默认本地地址。";
        }
        String host = hostOf(base.contains("://") ? base : "https://" + base).toLowerCase(Locale.ROOT);
        if (host.startsWith("platform.") || host.startsWith("console.") || host.startsWith("dashboard.")
                || host.startsWith("www.")) {
            return "这个地址看起来像网页/控制台，而不是 API 地址；网页地址收到 POST 通常会返回 405 或一段 HTML。";
        }
        if (!base.contains("://")) {
            return "没有写 http:// 或 https://，将按 https:// 处理。";
        }
        return null;
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (Exception ignored) {
            return url;
        }
    }

    private static String trimSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String time(AiChatLog.Entry entry) {
        return TIME.format(Instant.ofEpochMilli(entry.timeMillis()));
    }

    private static long ceilSeconds(long millis) {
        return (millis + 999L) / 1000L;
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String trimNumber(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        while (text.contains(".") && (text.endsWith("0") || text.endsWith("."))) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 内部类型
    // ══════════════════════════════════════════════════════════════════════

    private enum Tab {
        CHAT,
        SETTINGS
    }

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** 可点击区域：左键动作与可选的右键动作。 */
    private record Hit(int x, int y, int w, int h, Runnable action, Runnable rightAction) {
        Hit(int x, int y, int w, int h, Runnable action) {
            this(x, y, w, h, action, null);
        }

        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record StatusPill(String text, int color, boolean animated) {
    }

    /** 排版后的消息块。 */
    private static final class Block {
        private final AiChatLog.Entry entry;
        private List<FormattedCharSequence> lines = List.of();
        private int height;
        private int bubbleW;
        private int totalLines;
        /** 多行系统消息是否以卡片形式显示。 */
        private boolean boxed;
        private int y;

        private Block(AiChatLog.Entry entry) {
            this.entry = entry;
        }
    }
}
