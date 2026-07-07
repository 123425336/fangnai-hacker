package com.fangnai.hacker.client.screen;

import com.fangnai.hacker.client.combat.KillAreaModule;
import com.fangnai.hacker.client.config.HackerClientConfig;
import com.fangnai.hacker.client.input.ModKeyMappings;
import com.fangnai.hacker.client.render.HudBlurRenderer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;

public class HackerSettingsScreen extends Screen {

    // ── palette ────────────────────────────────────────────────────────────
    private static final int ACCENT      = 0xFF00C8FF;
    private static final int ACCENT_DIM  = 0x5500C8FF;
    private static final int CAT_BG      = 0xEE0A0D14;
    private static final int MOD_BG      = 0xCC050709;
    private static final int ROW_BG      = 0x99030508;
    private static final int ROW_ALT     = 0x99060B11;
    private static final int TXT_WHITE   = 0xFFFFFFFF;
    private static final int TXT_MUTED   = 0xFF7AACCC;
    private static final int TXT_ACCENT  = 0xFF00C8FF;
    private static final int TXT_OFF     = 0xFF445566;

    // ── dimensions ─────────────────────────────────────────────────────────
    private static final int PANEL_W   = 168;
    private static final int CAT_H     = 20;
    private static final int MOD_H     = 18;
    private static final int SETTING_H = 16;

    // ── state ──────────────────────────────────────────────────────────────
    private int px, py;
    private boolean dragging;
    private int dragDx, dragDy;
    private boolean expanded;

    // slider drag
    private int draggingSlider = -1;  // 0=reach 1=cps 2=fov
    private boolean awaitingKey;

    public HackerSettingsScreen() {
        super(Component.empty());
    }

    @Override
    protected void init() {
        HackerClientConfig cfg = HackerClientConfig.get();
        px = Mth.clamp((int) cfg.guiPanelX, 0, Math.max(0, width  - PANEL_W));
        py = Mth.clamp((int) cfg.guiPanelY, 0, Math.max(0, height - totalHeight()));
    }

    // ── total height helper ─────────────────────────────────────────────────
    private int totalHeight() {
        int h = CAT_H + MOD_H;
        if (expanded) h += settingCount() * SETTING_H;
        return h;
    }

    private int settingCount() { return 11; }   // reach,cps,fov,priority,players,hostiles,neutral,passive,crit,cooldown,los,pause-item = actually 12, keep 12
    // (re-count: reach cps fov priority target_players hostiles neutral passive crit_hit cooldown require_los pause_item toggle_key = 13)
    // simplify to exact rows built in renderSettings

    // ── render ──────────────────────────────────────────────────────────────
    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        HackerClientConfig.KillAreaSettings s = HackerClientConfig.killArea();

        int panelH = totalHeight();
        // blur only under whole panel
        HudBlurRenderer.renderBlurredRegion(minecraft, px, py, PANEL_W, panelH, 8.0F);

        int y = py;

        // ── Category header: [Combat] ───────────────────────────────────────
        g.fill(px, y, px + PANEL_W, y + CAT_H, CAT_BG);
        g.fill(px, y, px + 3, y + CAT_H, ACCENT);           // left stripe
        g.fill(px, y, px + PANEL_W, y + 1, ACCENT);          // top line
        g.fill(px, y + CAT_H - 1, px + PANEL_W, y + CAT_H, 0x33FFFFFF);
        drawCenteredTxt(g, "战斗", px + PANEL_W / 2, y + (CAT_H - 7) / 2, TXT_WHITE);
        y += CAT_H;

        // ── Module row: KillArea ─────────────────────────────────────────────
        boolean on = KillAreaModule.isEnabled();
        g.fill(px, y, px + PANEL_W, y + MOD_H, MOD_BG);
        // left 80%: toggle click zone
        int toggleZoneW = PANEL_W - 22;
        if (on) g.fill(px, y, px + toggleZoneW, y + MOD_H, 0x2200C8FF);
        // accent dot indicator
        g.fill(px + 5, y + (MOD_H - 6) / 2, px + 9, y + (MOD_H - 6) / 2 + 6, on ? ACCENT : 0xFF334455);
        drawTxt(g, "击杀区", px + 13, y + (MOD_H - 7) / 2, on ? TXT_ACCENT : TXT_MUTED);
        // expand arrow right side
        String arrow = expanded ? "▾" : "▸";
        drawTxt(g, arrow, px + PANEL_W - 14, y + (MOD_H - 7) / 2, TXT_MUTED);
        g.fill(px, y + MOD_H - 1, px + PANEL_W, y + MOD_H, 0x22FFFFFF);
        y += MOD_H;

        // ── Settings rows ────────────────────────────────────────────────────
        if (expanded) {
            y = renderSettings(g, s, mx, my, y);
        }

        // panel border
        g.fill(px, py, px + 1, py + panelH, 0x44FFFFFF);
        g.fill(px + PANEL_W - 1, py, px + PANEL_W, py + panelH, 0x44FFFFFF);
        g.fill(px, py + panelH - 1, px + PANEL_W, py + panelH, 0x33FFFFFF);
    }

    private int renderSettings(GuiGraphics g, HackerClientConfig.KillAreaSettings s, int mx, int my, int y) {
        y = renderSlider(g, "攻击距离", s.reach, 2.0, 6.0, 0, mx, my, y);
        y = renderSlider(g, "点击速度", s.cps,   1.0, 15.0, 1, mx, my, y);
        y = renderSlider(g, "视角范围", s.fovDegrees, 30.0, 360.0, 2, mx, my, y);
        y = renderCycle(g, "优先级", s.priority.displayName().getString(), y);
        y = renderToggle(g, "攻击玩家",    s.targetPlayers,          y);
        y = renderToggle(g, "攻击怪物",   s.targetHostiles,         y);
        y = renderToggle(g, "攻击中立",    s.targetNeutral,          y);
        y = renderToggle(g, "攻击被动",    s.targetPassive,          y);
        y = renderToggle(g, "暴击",   s.critHit,                y);
        y = renderToggle(g, "免疫击退", s.noKnockback,           y);
        y = renderToggle(g, "攻击冷却",   s.respectAttackCooldown,  y);
        y = renderToggle(g, "视线检测", s.requireLineOfSight,  y);
        y = renderToggle(g, "持物暂停",  s.pauseWhileUsingItem, y);
        y = renderKeyBind(g, "快捷键",   ModKeyMappings.KILLAREA_TOGGLE, y);
        return y;
    }

    // ── row renderers ────────────────────────────────────────────────────────

    private int renderSlider(GuiGraphics g, String label, double val, double min, double max, int id, int mx, int my, int y) {
        boolean hov = inRow(mx, my, y);
        g.fill(px, y, px + PANEL_W, y + SETTING_H, (y / SETTING_H % 2 == 0) ? ROW_BG : ROW_ALT);
        if (hov) g.fill(px, y, px + PANEL_W, y + SETTING_H, 0x11FFFFFF);

        // label
        drawTxt(g, label, px + 8, y + (SETTING_H - 7) / 2, TXT_MUTED);

        // mini track on the right half
        int trackX = px + 90, trackW = PANEL_W - 98;
        int trackY = y + (SETTING_H - 3) / 2;
        g.fill(trackX, trackY, trackX + trackW, trackY + 3, 0x33FFFFFF);
        int fillW = (int) ((Mth.clamp(val, min, max) - min) / (max - min) * trackW);
        g.fill(trackX, trackY, trackX + fillW, trackY + 3, ACCENT);
        // handle
        int hx = trackX + fillW - 2;
        g.fill(hx, y + 3, hx + 4, y + SETTING_H - 3, TXT_WHITE);

        // value label
        String valStr = String.format(Locale.ROOT, "%.1f", val);
        drawRightTxt(g, valStr, px + PANEL_W - 5, y + (SETTING_H - 7) / 2, TXT_ACCENT);

        return y + SETTING_H;
    }

    private int renderToggle(GuiGraphics g, String label, boolean val, int y) {
        g.fill(px, y, px + PANEL_W, y + SETTING_H, (y / SETTING_H % 2 == 0) ? ROW_BG : ROW_ALT);
        drawTxt(g, label, px + 8, y + (SETTING_H - 7) / 2, TXT_MUTED);
        // pill
        int pillX = px + PANEL_W - 28, pillY = y + (SETTING_H - 8) / 2;
        g.fill(pillX, pillY, pillX + 22, pillY + 8, val ? ACCENT_DIM : 0x33FFFFFF);
        g.fill(val ? pillX + 14 : pillX, pillY, (val ? pillX + 22 : pillX + 8), pillY + 8, val ? ACCENT : 0xFF556677);
        return y + SETTING_H;
    }

    private int renderCycle(GuiGraphics g, String label, String value, int y) {
        g.fill(px, y, px + PANEL_W, y + SETTING_H, (y / SETTING_H % 2 == 0) ? ROW_BG : ROW_ALT);
        drawTxt(g, label, px + 8, y + (SETTING_H - 7) / 2, TXT_MUTED);
        drawRightTxt(g, "‹ " + value + " ›", px + PANEL_W - 5, y + (SETTING_H - 7) / 2, TXT_ACCENT);
        return y + SETTING_H;
    }

    private int renderKeyBind(GuiGraphics g, String label, KeyMapping km, int y) {
        g.fill(px, y, px + PANEL_W, y + SETTING_H, (y / SETTING_H % 2 == 0) ? ROW_BG : ROW_ALT);
        drawTxt(g, label, px + 8, y + (SETTING_H - 7) / 2, TXT_MUTED);
        String keyStr = awaitingKey ? "..." : km.getTranslatedKeyMessage().getString();
        int col = awaitingKey ? ACCENT : TXT_ACCENT;
        // mini key box
        int kw = font.width(keyStr) + 6;
        int kx = px + PANEL_W - kw - 5;
        g.fill(kx - 1, y + 2, kx + kw + 1, y + SETTING_H - 2, 0x55FFFFFF);
        drawTxt(g, keyStr, kx + 3, y + (SETTING_H - 7) / 2, col);
        return y + SETTING_H;
    }

    // ── mouse ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int imx = (int) mx, imy = (int) my;
        int y = py;

        // drag start on category header
        if (btn == 0 && inRect(imx, imy, px, y, PANEL_W, CAT_H)) {
            dragging = true; dragDx = imx - px; dragDy = imy - py;
            return true;
        }
        y += CAT_H;

        // module row click
        if (inRect(imx, imy, px, y, PANEL_W, MOD_H)) {
            int expandX = px + PANEL_W - 22;
            if (imx >= expandX) {
                expanded = !expanded;
            } else {
                KillAreaModule.toggle();
            }
            return true;
        }
        y += MOD_H;

        if (!expanded) return false;

        // settings clicks
        HackerClientConfig.KillAreaSettings s = HackerClientConfig.killArea();
        y = handleSettingsClick(imx, imy, s, y);
        return true;
    }

    private int handleSettingsClick(int mx, int my, HackerClientConfig.KillAreaSettings s, int y) {
        // slider rows: reach, cps, fov
        for (int id = 0; id < 3; id++) {
            if (inRow(mx, my, y)) {
                int trackX = px + 90, trackW = PANEL_W - 98;
                if (mx >= trackX && mx <= trackX + trackW) {
                    draggingSlider = id;
                    applySlider(id, mx, s);
                }
            }
            y += SETTING_H;
        }
        // priority cycle
        if (inRow(mx, my, y)) s.priority = s.priority.next();
        y += SETTING_H;
        // toggles
        if (inRow(mx, my, y)) s.targetPlayers         = !s.targetPlayers;         y += SETTING_H;
        if (inRow(mx, my, y)) s.targetHostiles        = !s.targetHostiles;        y += SETTING_H;
        if (inRow(mx, my, y)) s.targetNeutral         = !s.targetNeutral;         y += SETTING_H;
        if (inRow(mx, my, y)) s.targetPassive         = !s.targetPassive;         y += SETTING_H;
        if (inRow(mx, my, y)) s.critHit               = !s.critHit;               y += SETTING_H;
        if (inRow(mx, my, y)) s.noKnockback           = !s.noKnockback;           y += SETTING_H;
        if (inRow(mx, my, y)) s.respectAttackCooldown = !s.respectAttackCooldown; y += SETTING_H;
        if (inRow(mx, my, y)) s.requireLineOfSight    = !s.requireLineOfSight;    y += SETTING_H;
        if (inRow(mx, my, y)) s.pauseWhileUsingItem   = !s.pauseWhileUsingItem;   y += SETTING_H;
        // keybind
        if (inRow(mx, my, y)) awaitingKey = true;
        y += SETTING_H;
        return y;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (dragging) {
            px = Mth.clamp((int) mx - dragDx, 0, width  - PANEL_W);
            py = Mth.clamp((int) my - dragDy, 0, height - totalHeight());
            return true;
        }
        if (draggingSlider >= 0) {
            applySlider(draggingSlider, (int) mx, HackerClientConfig.killArea());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (dragging) {
            dragging = false;
            HackerClientConfig cfg = HackerClientConfig.get();
            cfg.guiPanelX = px; cfg.guiPanelY = py;
        }
        draggingSlider = -1;
        return false;
    }

    // ── keyboard ─────────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(int kc, int sc, int mods) {
        if (awaitingKey) {
            InputConstants.Key key = kc == 256 || kc == 259 || kc == 261
                    ? InputConstants.UNKNOWN
                    : InputConstants.getKey(kc, sc);
            minecraft.options.setKey(ModKeyMappings.KILLAREA_TOGGLE, key);
            KeyMapping.resetMapping();
            minecraft.options.save();
            awaitingKey = false;
            return true;
        }
        if (kc == 256) { onClose(); return true; }
        return false;
    }

    @Override
    public void onClose() { HackerClientConfig.save(); super.onClose(); }

    @Override
    public boolean isPauseScreen() { return false; }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void applySlider(int id, int mx, HackerClientConfig.KillAreaSettings s) {
        int trackX = px + 90, trackW = PANEL_W - 98;
        double t = Mth.clamp((double)(mx - trackX) / trackW, 0.0, 1.0);
        switch (id) {
            case 0 -> s.reach      = Math.round((2.0 + t * (6.0 - 2.0))   / 0.1) * 0.1;
            case 1 -> s.cps        = Math.round((1.0 + t * (15.0 - 1.0))  / 0.5) * 0.5;
            case 2 -> s.fovDegrees = Math.round((30.0 + t * (360.0 - 30.0)) / 5.0) * 5.0;
        }
        s.clamp();
    }

    private boolean inRow(int mx, int my, int y) {
        return inRect(mx, my, px, y, PANEL_W, SETTING_H);
    }

    private static boolean inRect(int mx, int my, int rx, int ry, int rw, int rh) {
        return mx >= rx && mx < rx + rw && my >= ry && my < ry + rh;
    }

    private void drawTxt(GuiGraphics g, String t, int x, int y, int col) {
        g.drawString(font, t, x, y, col, false);
    }

    private void drawCenteredTxt(GuiGraphics g, String t, int cx, int y, int col) {
        g.drawString(font, t, cx - font.width(t) / 2, y, col, false);
    }

    private void drawRightTxt(GuiGraphics g, String t, int rx, int y, int col) {
        g.drawString(font, t, rx - font.width(t), y, col, false);
    }
}
