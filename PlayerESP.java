package com.zenith.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.zenith.category.Category;
import com.zenith.module.Module;
import com.zenith.module.ModuleManager;
import com.zenith.setting.BooleanSetting;
import com.zenith.setting.Setting;
import com.zenith.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * ZenithScreen — visual style ported from Krypton client's ClickGUI.
 *
 * Fixes v2:
 *  - Smaller layout constants so the GUI fits at any MC GUI scale
 *  - Per-column scroll offset + max-height cap — handles 20+ modules
 *  - Keybind row rendered at the bottom of every expanded module's settings
 *  - Keybind row click -> listen for next key press, ESC clears bind
 */
public class ZenithScreen extends Screen {

    // ── Krypton Palette ───────────────────────────────────────────────────────
    private static final int C_PANEL      = rgba(25,  25,  30,  230);
    private static final int C_PANEL_HOV  = rgba(35,  35,  42,  230);
    private static final int C_HDR        = rgba(18,  18,  22,  245);
    private static final int C_SEP        = rgba(60,  60,  65,  100);
    private static final int C_ACCENT     = rgba(65,  105, 225, 255);
    private static final int C_ENABLED    = rgba(120, 190, 255, 255);
    private static final int C_DISABLED   = rgba(180, 180, 180, 255);
    private static final int C_TEXT       = rgba(255, 255, 255, 255);
    private static final int C_TOOLTIP_BG = rgba(40,  40,  40,  200);
    private static final int C_SETTING_BG = rgba(20,  20,  25,  220);
    private static final int C_BOX_BG     = rgba(40,  40,  45,  255);
    private static final int C_TRACK_BG   = rgba(60,  60,  65,  200);
    private static final int C_BIND_BG    = rgba(30,  30,  50,  220);
    private static final int C_BIND_ACT   = rgba(65,  105, 225, 180);

    // ── Layout — compact so GUI scale never needs changing ────────────────────
    private static final int COL_W          = 160;
    private static final int HDR_H          = 20;
    private static final int MOD_H          = 20;
    private static final int SET_H          = 22;  // slider needs a little more
    private static final int BIND_H         = 18;
    private static final int PAD            = 7;
    private static final int RADIUS         = 4;
    private static final int COL_GAP        = 10;
    private static final int MAX_COL_MARGIN = 16;  // gap kept at top+bottom of screen

    // ── Column positions, drag & per-column scroll ────────────────────────────
    private final Map<Category, Integer> colX      = new HashMap<>();
    private final Map<Category, Integer> colY      = new HashMap<>();
    private final Map<Category, Integer> colScroll = new HashMap<>();
    private boolean  draggingCol = false;
    private Category dragCat     = null;
    private int      dragOffX, dragOffY;

    // ── Per-module animation state ────────────────────────────────────────────
    private final Map<Module, Float>   hoverAnim  = new HashMap<>();
    private final Map<Module, Float>   enableAnim = new HashMap<>();
    private final Map<Module, Boolean> expanded   = new HashMap<>();

    // ── Slider drag ───────────────────────────────────────────────────────────
    private Module        sliderDragModule = null;
    private SliderSetting sliderDragSl     = null;

    // ── Keybind listening ─────────────────────────────────────────────────────
    private boolean listeningKey = false;
    private Module  keybindMod   = null;

    // ── Search ────────────────────────────────────────────────────────────────
    private String searchQuery = "";

    // ── Tooltip ───────────────────────────────────────────────────────────────
    private String tooltipText = null;
    private int    tooltipX, tooltipY;

    // ── Open animation ────────────────────────────────────────────────────────
    private final long openedAt;
    private static final long ANIM_MS = 200L;

    // ── Toasts — static so they survive GUI close and can be drawn on HUD ────
    private record Toast(String msg, long ts, boolean on) {}
    private static final Deque<Toast> toasts = new ArrayDeque<>();

    // ── 2x GUI scale regardless of MC setting ────────────────────────────────
    // guiS = 2.0 / mcGuiScale  (e.g. at MC scale 3: guiS = 0.667)
    // vw/vh = virtual screen size we render into after applying the matrix scale
    private float guiS = 1f;
    private int   vw, vh;

    // ─────────────────────────────────────────────────────────────────────────
    public ZenithScreen() {
        super(Component.literal("Zenith"));
        openedAt = System.currentTimeMillis();
    }

     public boolean shouldPause() { return false; }

    // Search bar sits at the very top; columns start below it
    private static final int SEARCH_H  = 18;
    private static final int SEARCH_Y  = 6;
    private static final int COL_START_Y = SEARCH_Y + SEARCH_H + 6;

    // ── Init — pack columns across the top ───────────────────────────────────
    @Override
    public void init() {
        int x = 8;
        for (Category cat : Category.values()) {
            colX.putIfAbsent(cat, x);
            colY.putIfAbsent(cat, COL_START_Y);
            colScroll.putIfAbsent(cat, 0);
            x += COL_W + COL_GAP;
        }
    }

    // ── Main render ───────────────────────────────────────────────────────────
    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        // Compute scale: target 2x physical pixels regardless of MC GUI scale
        double mcScale = Minecraft.getInstance().getWindow().getGuiScale();
        guiS = (float)(2.0 / mcScale);
        vw   = (int)(width  / guiS);
        vh   = (int)(height / guiS);

        // Convert mouse to virtual space
        int vmx = (int)(mx / guiS);
        int vmy = (int)(my / guiS);

        float anim = easeOut(Math.min(1f,
                (System.currentTimeMillis() - openedAt) / (float) ANIM_MS));

        tooltipText = null;

        // Push matrix scale so all drawing is at 2x physical pixels
        g.pose().pushMatrix();
        g.pose().scale(guiS, guiS, g.pose());

        g.fill(0, 0, vw, vh, scaleAlpha(0x55000000, anim));

        renderSearchBar(g, vmx, vmy, anim);

        for (Category cat : Category.values()) {
            renderColumn(g, cat, vmx, vmy, delta, anim);
        }

        if (tooltipText != null) {
            renderTooltip(g, tooltipText, tooltipX, tooltipY, anim);
        }

        if (listeningKey && keybindMod != null) {
            String msg = "Press a key for [" + keybindMod.getName() + "]  ESC = clear";
            int mw   = font.width(msg) + 16;
            int msgX = (vw - mw) / 2;
            int msgY = vh - 28;
            roundRect(g, msgX, msgY, mw, 16, 4, scaleAlpha(rgba(20, 20, 30, 220), anim));
            g.drawString(font, msg, msgX + 8, msgY + 4, scaleAlpha(C_ENABLED, anim), false);
        }

        drawToasts(g, vw, vh);

        g.pose().popMatrix();

        super.render(g, mx, my, delta);
    }

    // ── Search bar ────────────────────────────────────────────────────────────
    private void renderSearchBar(GuiGraphics g, int mx, int my, float anim) {
        int barW = Math.min(vw - 16, COL_W * 2);
        int bx   = (vw - barW) / 2;
        int by   = SEARCH_Y;

        // Background pill
        roundRect(g, bx, by, barW, SEARCH_H, SEARCH_H / 2,
                scaleAlpha(rgba(18, 18, 22, 220), anim));

        // Accent left edge when active
        if (!searchQuery.isEmpty()) {
            roundRect(g, bx, by, 3, SEARCH_H, 1,
                    scaleAlpha(C_ACCENT, anim));
        }

        // Icon — use a simple bracket-s since MC's font has no emoji
        g.drawString(font, "[S]", bx + 5, by + (SEARCH_H - 8) / 2,
                scaleAlpha(C_DISABLED, anim * 0.6f), false);

        // Query text or placeholder
        String display = searchQuery.isEmpty() ? "Search modules..." : searchQuery;
        int    dispCol = searchQuery.isEmpty()
                ? scaleAlpha(rgba(100, 100, 110, 255), anim)
                : scaleAlpha(C_TEXT, anim);
        g.drawString(font, display, bx + 26, by + (SEARCH_H - 8) / 2, dispCol, false);

        // Blinking cursor (only when something typed)
        if (!searchQuery.isEmpty() && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cursorX = bx + 26 + font.width(searchQuery);
            g.fill(cursorX, by + 4, cursorX + 1, by + SEARCH_H - 4,
                    scaleAlpha(C_ACCENT, anim));
        }

        // Result count hint
        if (!searchQuery.isEmpty()) {
            int total = 0;
            for (Category cat : Category.values()) {
                total += getFilteredModules(cat).size();
            }
            String hint = total + " result" + (total == 1 ? "" : "s");
            g.drawString(font, hint, bx + barW - font.width(hint) - 10,
                    by + (SEARCH_H - 8) / 2,
                    scaleAlpha(C_DISABLED, anim * 0.7f), false);
        }
    }

    // ── Column / category window ──────────────────────────────────────────────
    private void renderColumn(GuiGraphics g, Category cat, int mx, int my,
                              float delta, float anim) {
        int cx     = colX.getOrDefault(cat, 0);
        int cy     = colY.getOrDefault(cat, 0);
        int scroll = colScroll.getOrDefault(cat, 0);
        List<Module> mods = getFilteredModules(cat);

        // Full content height (including any expanded settings)
        int fullH = HDR_H;
        for (Module m : mods) {
            fullH += MOD_H;
            if (expanded.getOrDefault(m, false)) {
                fullH += m.getSettings().size() * SET_H + BIND_H;
            }
        }

        // Visible height is capped so the column never overflows the screen
        int maxVis    = Math.min(fullH, vh - cy - MAX_COL_MARGIN);
        int maxScroll = Math.max(0, fullH - maxVis);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        colScroll.put(cat, scroll);

        int visH = Math.min(fullH, maxVis);

        // Panel background
        boolean colHov = hov(mx, my, cx, cy, COL_W, visH);
        int panelBg = colHov ? blendColor(C_PANEL, C_PANEL_HOV, 0.25f) : C_PANEL;
        roundRect(g, cx, cy, COL_W, visH, RADIUS, scaleAlpha(panelBg, anim));

        // Header
        roundRectTop(g, cx, cy, COL_W, HDR_H, RADIUS, scaleAlpha(C_HDR, anim));
        String catLabel = cat.displayName.toUpperCase();
        int lw = font.width(catLabel);
        int lx = cx + (COL_W - lw) / 2;
        int ly = cy + (HDR_H - 8) / 2;
        g.drawString(font, catLabel, lx + 1, ly + 1,
                scaleAlpha(rgba(0, 0, 0, 100), anim), false);
        g.drawString(font, catLabel, lx, ly,
                scaleAlpha(brighten(cat.color | 0xFF000000), anim), false);
        g.fill(cx + 3, cy + HDR_H - 1, cx + COL_W - 3, cy + HDR_H,
                scaleAlpha(C_SEP, anim));

        // Scissor clip — module rows are clipped inside the visible area
        int clipTop    = cy + HDR_H;
        int clipBottom = cy + visH;
        if (clipBottom <= clipTop) return;

        g.enableScissor(cx, clipTop, cx + COL_W, clipBottom);

        int ry = cy + HDR_H - scroll;
        for (int idx = 0; idx < mods.size(); idx++) {
            Module m    = mods.get(idx);
            boolean last = (idx == mods.size() - 1);
            ry = renderModuleRow(g, m, cat, cx, cy, ry, idx, last,
                    mx, my, delta, anim);
        }

        g.disableScissor();

        // Scroll indicator bar on the right edge (only when content overflows)
        if (fullH > visH && visH > HDR_H) {
            int barAreaH = visH - HDR_H;
            int barH     = Math.max(14, barAreaH * barAreaH / fullH);
            int barY     = cy + HDR_H + (int)((long) scroll * (barAreaH - barH)
                    / Math.max(1, maxScroll));
            g.fill(cx + COL_W - 2, cy + HDR_H,
                    cx + COL_W,     cy + visH,
                    scaleAlpha(rgba(50, 50, 60, 160), anim));
            roundRect(g, cx + COL_W - 2, barY, 2, barH, 1,
                    scaleAlpha(C_ACCENT, anim * 0.8f));
        }
    }

    // ── Module row ────────────────────────────────────────────────────────────
    private int renderModuleRow(GuiGraphics g, Module m, Category cat,
                                int cx, int cy, int ry, int idx, boolean isLast,
                                int mx, int my, float delta, float anim) {
        boolean on   = m.isEnabled();
        boolean ext  = expanded.getOrDefault(m, false);
        boolean hovM = hov(mx, my, cx, ry, COL_W, MOD_H);

        // Animations
        float spd = delta * 0.05f;
        float hv  = expLerp(hoverAnim.getOrDefault(m, 0f), hovM ? 1f : 0f, 0.05, spd);
        float en  = expLerp(enableAnim.getOrDefault(m, on ? 1f : 0f), on ? 1f : 0f, 0.005, spd);
        en = Math.max(0f, Math.min(1f, en));
        hoverAnim.put(m, hv);
        enableAnim.put(m, en);

        // Row background
        int rowBg   = blendColor(C_PANEL, rgba(255, 255, 255, 20), hv * 0.08f);
        boolean lastRow = isLast && !ext;
        if (lastRow) {
            roundRectBottom(g, cx, ry, COL_W, MOD_H, RADIUS, scaleAlpha(rowBg, anim));
        } else {
            g.fill(cx, ry, cx + COL_W, ry + MOD_H, scaleAlpha(rowBg, anim));
        }
        if (idx > 0) {
            g.fill(cx + 3, ry, cx + COL_W - 3, ry + 1, scaleAlpha(C_SEP, anim));
        }

        // Left indicator bar
        float indW = 5f * en;
        if (indW > 0.1f) {
            int indColor = blendColor(C_DISABLED, cat.color | 0xFF000000, en);
            roundRect(g, cx, ry + 2, (int) indW, MOD_H - 4, 2,
                    scaleAlpha(indColor, anim));
        }

        // Module name
        int nameCol = blendColor(C_DISABLED, C_ENABLED, en);
        g.drawString(font, m.getName(), cx + PAD + 2, ry + (MOD_H - 8) / 2,
                scaleAlpha(nameCol, anim), false);

        // Toggle switch
        renderToggle(g, cx, ry, en, anim);

        // Expand arrow (only if module has settings)
        if (!m.getSettings().isEmpty()) {
            String arrow = ext ? "\u25b2" : "\u25bc";
            g.drawString(font, arrow, cx + COL_W - 10, ry + (MOD_H - 8) / 2,
                    scaleAlpha(C_DISABLED, anim * 0.7f), false);
        }

        // Tooltip on hover
        if (hovM && !draggingCol) {
            tooltipText = m.getName();
            tooltipX    = mx + 12;
            tooltipY    = my + 12;
        }

        int nextY = ry + MOD_H;

        // Inline settings expansion
        if (ext) {
            List<Setting<?>> settings = m.getSettings();
            int totalSettingRows = settings.size() + 1; // +1 for keybind
            for (int si = 0; si < settings.size(); si++) {
                Setting<?> s   = settings.get(si);
                boolean lastS  = isLast && (si == totalSettingRows - 1);
                // background
                if (lastS) {
                    roundRectBottom(g, cx, nextY, COL_W, SET_H, RADIUS,
                            scaleAlpha(C_SETTING_BG, anim));
                } else {
                    g.fill(cx, nextY, cx + COL_W, nextY + SET_H,
                            scaleAlpha(C_SETTING_BG, anim));
                }
                g.fill(cx + 3, nextY, cx + COL_W - 3, nextY + 1,
                        scaleAlpha(C_SEP, anim));
                renderSettingRow(g, s, cx, nextY, mx, my, anim);
                nextY += SET_H;
            }
            // Keybind row — always the last row of the expansion
            boolean lastBind = isLast;
            if (lastBind) {
                roundRectBottom(g, cx, nextY, COL_W, BIND_H, RADIUS,
                        scaleAlpha(C_SETTING_BG, anim));
            } else {
                g.fill(cx, nextY, cx + COL_W, nextY + BIND_H,
                        scaleAlpha(C_SETTING_BG, anim));
            }
            g.fill(cx + 3, nextY, cx + COL_W - 3, nextY + 1,
                    scaleAlpha(C_SEP, anim));
            renderKeybindRow(g, m, cx, nextY, mx, my, anim);
            nextY += BIND_H;
        }

        return nextY;
    }

    // ── Toggle switch ─────────────────────────────────────────────────────────
    private void renderToggle(GuiGraphics g, int cx, int ry, float en, float anim) {
        int tw = 18, th = 10;
        int tx = cx + COL_W - 34; // sits left of the arrow at COL_W-10
        int ty = ry + (MOD_H - th) / 2;

        int trackCol = blendColor(C_TRACK_BG, rgba(65, 105, 225, 100), en);
        roundRect(g, tx, ty, tw, th, th / 2, scaleAlpha(trackCol, anim));

        float kx     = tx + 5 + (tw - 10) * en;
        int knobCol  = blendColor(C_DISABLED, C_ENABLED, en);
        roundRect(g, (int) kx - 4, ty,     8, th,     th / 2,     scaleAlpha(rgba(0,0,0,80), anim));
        roundRect(g, (int) kx - 3, ty + 1, 6, th - 2, (th-2) / 2, scaleAlpha(knobCol, anim));
        roundRect(g, (int) kx - 2, ty + 1, 4, 3,       2,          scaleAlpha(rgba(255,255,255,50), anim));
    }

    // ── Setting row ───────────────────────────────────────────────────────────
    private void renderSettingRow(GuiGraphics g, Setting<?> s, int cx, int sy,
                                  int mx, int my, float anim) {
        int vc      = sy + (SET_H - 8) / 2;
        int textCol = scaleAlpha(C_DISABLED, anim);

        if (s instanceof SliderSetting sl) {
            g.drawString(font, s.getName(), cx + PAD + 6, sy + 3, textCol, false);
            String val = String.valueOf(sl.getValue().longValue());
            g.drawString(font, val,
                    cx + COL_W - font.width(val) - PAD, sy + 3, textCol, false);

            int tX = cx + PAD + 6;
            int tY = sy + 14;
            int tW = COL_W - PAD * 2 - 6;
            int tH = 3;
            roundRect(g, tX, tY, tW, tH, 1, scaleAlpha(C_TRACK_BG, anim));
            int filled = (int)(tW * sl.getPct());
            if (filled > 1)
                roundRect(g, tX, tY, filled, tH, 1, scaleAlpha(C_ACCENT, anim));
            int kx = tX + Math.max(filled, 2);
            roundRect(g, kx - 4, tY - 2, 8, tH + 4, 3, scaleAlpha(rgba(0,0,0,100), anim));
            roundRect(g, kx - 3, tY - 1, 6, tH + 2, 3, scaleAlpha(C_ACCENT, anim));
            roundRect(g, kx - 2, tY - 1, 4, 2,       1, scaleAlpha(rgba(255,255,255,70), anim));

        } else if (s instanceof BooleanSetting bl) {
            g.drawString(font, s.getName(), cx + PAD + 6, vc, textCol, false);
            int bsz = 10;
            int bx  = cx + COL_W - bsz - PAD;
            int by  = sy + (SET_H - bsz) / 2;
            roundRect(g, bx - 1, by - 1, bsz + 2, bsz + 2, 2,
                    scaleAlpha(rgba(100, 100, 110, 255), anim));
            roundRect(g, bx, by, bsz, bsz, 2,
                    scaleAlpha(bl.getValue() ? C_ACCENT : C_BOX_BG, anim));
            if (bl.getValue()) {
                g.drawString(font, "\u2714", bx + 1, by, scaleAlpha(C_TEXT, anim), false);
            }
        }
    }

    // ── Keybind row ───────────────────────────────────────────────────────────
    private void renderKeybindRow(GuiGraphics g, Module m, int cx, int ry,
                                  int mx, int my, float anim) {
        boolean listening = listeningKey && keybindMod == m;
        boolean hovRow    = hov(mx, my, cx, ry, COL_W, BIND_H);

        int vc      = ry + (BIND_H - 8) / 2;
        int textCol = scaleAlpha(C_DISABLED, anim);
        g.drawString(font, "Bind", cx + PAD + 6, vc, textCol, false);

        String keyLabel = listening ? "..." : keyName(m.getKeyCode());
        int kw  = font.width(keyLabel) + 10;
        int kx  = cx + COL_W - kw - PAD;
        int ky  = ry + (BIND_H - 11) / 2;
        int pillCol = listening  ? C_BIND_ACT
                : hovRow     ? blendColor(C_BIND_BG, C_BIND_ACT, 0.4f)
                : C_BIND_BG;
        roundRect(g, kx, ky, kw, 11, 3, scaleAlpha(pillCol, anim));
        g.drawString(font, keyLabel, kx + 5, ky + 2, scaleAlpha(C_TEXT, anim), false);
    }

    // ── Tooltip ───────────────────────────────────────────────────────────────
    private void renderTooltip(GuiGraphics g, String text, int tx, int ty, float anim) {
        int tw = font.width(text);
        if (tx + tw + 10 > width) tx = width - tw - 10;
        roundRect(g, tx - 4, ty - 4, tw + 8, 16, RADIUS, scaleAlpha(C_TOOLTIP_BG, anim));
        g.drawString(font, text, tx + 1, ty + 2,
                scaleAlpha(rgba(0, 0, 0, 120), anim), false);
        g.drawString(font, text, tx, ty + 1, scaleAlpha(C_TEXT, anim), false);
    }

    // ── Toasts — drawn in-GUI ─────────────────────────────────────────────────
    private void drawToasts(GuiGraphics g, int screenW, int screenH) {
        renderHudToasts(g, screenW, screenH);
    }

    /**
     * Call this from your HUD render event so toasts appear even when the
     * ClickGUI is closed. Pass the real (unscaled) GUI screen width/height.
     *   e.g. in your HudRenderCallback:
     *       ZenithScreen.renderHudToasts(graphics, mc.getWindow().getGuiScaledWidth(),
     *                                              mc.getWindow().getGuiScaledHeight());
     */
    public static void renderHudToasts(GuiGraphics g, int screenW, int screenH) {
        int bx = screenW - 120, by = screenH - 20;
        long now = System.currentTimeMillis();
        List<Toast> alive = toasts.stream().filter(t -> now - t.ts() < 2200).toList();
        toasts.clear();
        toasts.addAll(alive);
        var font = Minecraft.getInstance().font;
        for (int i = alive.size() - 1; i >= 0; i--) {
            Toast t   = alive.get(i);
            long  age = now - t.ts();
            float a   = age < 150 ? age / 150f : age > 1900 ? 1f - (age-1900) / 300f : 1f;
            int   al  = (int)(a * 200) << 24;
            int   ty2 = by - i * 14 + (int)((1 - a) * 8);
            roundRect(g, bx, ty2, 110, 12, 3, al | 0x15151A);
            g.fill(bx, ty2, bx + 2, ty2 + 12, al | (t.on() ? 0x4169E1 : 0x555555));
            g.drawString(font, (t.on() ? "\u00a7a\u25b6 " : "\u00a77\u25a0 ") + t.msg(),
                    bx + 5, ty2 + 2, al | 0xCCCCCC, false);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Input events — 1.21.11 Mojang/Fabric API
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx     = (int)(event.x() / guiS);
        int my     = (int)(event.y() / guiS);
        int button = event.button();

        if (listeningKey && keybindMod != null) return true;

        for (Category cat : Category.values()) {
            int cx     = colX.getOrDefault(cat, 0);
            int cy     = colY.getOrDefault(cat, 0);
            int scroll = colScroll.getOrDefault(cat, 0);
            List<Module> mods = getFilteredModules(cat);

            // Drag column header
            if (hov(mx, my, cx, cy, COL_W, HDR_H) && button == 0) {
                draggingCol = true;
                dragCat     = cat;
                dragOffX    = mx - cx;
                dragOffY    = my - cy;
                return true;
            }

            int ry = cy + HDR_H - scroll;
            for (int idx = 0; idx < mods.size(); idx++) {
                Module m = mods.get(idx);

                // Module row
                if (hov(mx, my, cx, ry, COL_W, MOD_H)) {
                    if (button == 0) {
                        int toggleX   = cx + COL_W - 34;
                        int toggleY   = ry + (MOD_H - 10) / 2;
                        boolean onToggle = mx >= toggleX && mx <= toggleX + 18
                                && my >= toggleY && my <= toggleY + 10;
                        boolean onArrow  = !m.getSettings().isEmpty()
                                && mx > cx + COL_W - 14;
                        if (onToggle || (!onArrow && !onToggle)) {
                            m.toggle();
                            toast(m.getName(), m.isEnabled());
                        }
                        if (onArrow) {
                            expanded.put(m, !expanded.getOrDefault(m, false));
                        }
                    } else if (button == 1) {
                        if (!m.getSettings().isEmpty())
                            expanded.put(m, !expanded.getOrDefault(m, false));
                    }
                    return true;
                }
                ry += MOD_H;

                // Settings rows (if expanded)
                if (expanded.getOrDefault(m, false)) {
                    for (Setting<?> s : m.getSettings()) {
                        if (hov(mx, my, cx, ry, COL_W, SET_H) && button == 0) {
                            if (s instanceof BooleanSetting bl) {
                                bl.toggle();
                                return true;
                            }
                            if (s instanceof SliderSetting sl) {
                                sliderDragModule = m;
                                sliderDragSl     = sl;
                                int tX = cx + PAD + 6;
                                int tW = COL_W - PAD * 2 - 6;
                                double p = Math.max(0, Math.min(1,
                                        (mx - tX) / (double) tW));
                                sl.setValue(sl.getMin()
                                        + (sl.getMax() - sl.getMin()) * p);
                                return true;
                            }
                        }
                        ry += SET_H;
                    }
                    // Keybind row click
                    if (hov(mx, my, cx, ry, COL_W, BIND_H) && button == 0) {
                        listeningKey = true;
                        keybindMod   = m;
                        return true;
                    }
                    ry += BIND_H;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double ddx, double ddy) {
        int mx     = (int)(event.x() / guiS);
        int my     = (int)(event.y() / guiS);
        int button = event.button();

        if (draggingCol && dragCat != null && button == 0) {
            colX.put(dragCat, Math.max(0, Math.min(vw - COL_W, mx - dragOffX)));
            colY.put(dragCat, Math.max(0, Math.min(vh - HDR_H, my - dragOffY)));
            return true;
        }

        if (sliderDragModule != null && sliderDragSl != null) {
            int cx = colX.getOrDefault(findCategory(sliderDragModule), 0);
            int tX = cx + PAD + 6;
            int tW = COL_W - PAD * 2 - 6;
            double p = Math.max(0, Math.min(1, (mx - tX) / (double) tW));
            sliderDragSl.setValue(sliderDragSl.getMin()
                    + (sliderDragSl.getMax() - sliderDragSl.getMin()) * p);
            return true;
        }

        return super.mouseDragged(event, ddx, ddy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) {
            draggingCol      = false; dragCat = null;
            sliderDragModule = null;  sliderDragSl = null;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double dmx, double dmy, double hScroll, double vScroll) {
        int mx = (int)(dmx / guiS), my = (int)(dmy / guiS);
        for (Category cat : Category.values()) {
            int cx = colX.getOrDefault(cat, 0);
            int cy = colY.getOrDefault(cat, 0);
            if (mx >= cx && mx < cx + COL_W && my >= cy) {
                int cur = colScroll.getOrDefault(cat, 0);
                colScroll.put(cat, Math.max(0, cur - (int)(vScroll * 15)));
                return true;
            }
        }
        return super.mouseScrolled(dmx, dmy, hScroll, vScroll);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();

        if (listeningKey && keybindMod != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                keybindMod.setKeyCode(-1);
            } else {
                keybindMod.setKeyCode(keyCode);
            }
            listeningKey = false;
            keybindMod   = null;
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (!searchQuery.isEmpty()) {
                searchQuery = "";
                for (Category cat : Category.values()) colScroll.put(cat, 0);
                return true;
            }
            onClose();
            return true;
        }

        // Backspace — delete last character from search
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !listeningKey) {
            if (!searchQuery.isEmpty()) {
                searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                for (Category cat : Category.values()) colScroll.put(cat, 0);
                return true;
            }
        }

        // Type into search bar — letters A-Z and digits 0-9
        if (!listeningKey) {
            char typed = 0;
            if (keyCode >= GLFW.GLFW_KEY_A && keyCode <= GLFW.GLFW_KEY_Z) {
                typed = (char) ('a' + (keyCode - GLFW.GLFW_KEY_A));
            } else if (keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9) {
                typed = (char) ('0' + (keyCode - GLFW.GLFW_KEY_0));
            }
            if (typed != 0) {
                searchQuery += typed;
                for (Category cat : Category.values()) colScroll.put(cat, 0);
                return true;
            }
        }

        return super.keyPressed(event);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Module> getModules(Category cat) {
        return ModuleManager.getInstance().getModulesByCategory(cat);
    }

    /** Returns modules in this category that match the current search query (case-insensitive). */
    private List<Module> getFilteredModules(Category cat) {
        List<Module> all = getModules(cat);
        if (searchQuery.isEmpty()) return all;
        String q = searchQuery.toLowerCase();
        List<Module> out = new ArrayList<>();
        for (Module m : all) {
            if (m.getName().toLowerCase().contains(q)) out.add(m);
        }
        return out;
    }

    private Category findCategory(Module m) {
        return m.getCategory();
    }

    private boolean hov(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static void toast(String msg, boolean on) {
        toasts.offer(new Toast(msg, System.currentTimeMillis(), on));
        if (toasts.size() > 3) toasts.poll();
    }

    private static float easeOut(float t) {
        return 1f - (float) Math.pow(1f - t, 3);
    }

    private static float expLerp(float current, float target, double factor, float delta) {
        return (float)(target + (current - target) * Math.pow(factor, delta));
    }

    private static int brighten(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 40);
        int g = Math.min(255, ((argb >>  8) & 0xFF) + 40);
        int b = Math.min(255, ( argb        & 0xFF) + 40);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ── Rounded rect helpers ──────────────────────────────────────────────────

    private static void roundRect(GuiGraphics g, int x, int y, int w, int h, int r, int c) {
        if (r <= 0 || w < r * 2 || h < r * 2) { g.fill(x, y, x + w, y + h, c); return; }
        g.fill(x + r, y,         x + w - r, y + h,     c);
        g.fill(x,     y + r,     x + r,     y + h - r, c);
        g.fill(x + w - r, y + r, x + w,     y + h - r, c);
        for (int i = 0; i < r; i++) {
            double angle = Math.acos((double)(r - i) / r);
            int off = r - (int)(r * Math.cos(angle));
            g.fill(x + off,   y + i,         x + r,       y + i + 1,     c);
            g.fill(x + w - r, y + i,         x + w - off, y + i + 1,     c);
            g.fill(x + off,   y + h - i - 1, x + r,       y + h - i,     c);
            g.fill(x + w - r, y + h - i - 1, x + w - off, y + h - i,     c);
        }
    }

    private static void roundRectTop(GuiGraphics g, int x, int y, int w, int h, int r, int c) {
        if (r <= 0) { g.fill(x, y, x + w, y + h, c); return; }
        g.fill(x + r, y,     x + w - r, y + h, c);
        g.fill(x,     y + r, x + r,     y + h, c);
        g.fill(x + w - r, y + r, x + w, y + h, c);
        for (int i = 0; i < r; i++) {
            double angle = Math.acos((double)(r - i) / r);
            int off = r - (int)(r * Math.cos(angle));
            g.fill(x + off,   y + i, x + r,       y + i + 1, c);
            g.fill(x + w - r, y + i, x + w - off, y + i + 1, c);
        }
    }

    private static void roundRectBottom(GuiGraphics g, int x, int y, int w, int h, int r, int c) {
        if (r <= 0) { g.fill(x, y, x + w, y + h, c); return; }
        g.fill(x, y, x + w, y + h - r, c);
        g.fill(x,         y + h - r, x + r,     y + h, c);
        g.fill(x + w - r, y + h - r, x + w,     y + h, c);
        for (int i = 0; i < r; i++) {
            double angle = Math.acos((double)(r - i) / r);
            int off = r - (int)(r * Math.cos(angle));
            g.fill(x + off,   y + h - i - 1, x + r,       y + h - i, c);
            g.fill(x + w - r, y + h - i - 1, x + w - off, y + h - i, c);
        }
    }

    // ── Colour helpers ────────────────────────────────────────────────────────

    private static int rgba(int r, int g, int b, int a) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int scaleAlpha(int argb, float anim) {
        int origA = (argb >> 24) & 0xFF;
        return ((int)(origA * anim) << 24) | (argb & 0x00FFFFFF);
    }

    private static int blendColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >> 24) & 0xFF, ar = (a >> 16) & 0xFF,
                ag = (a >>  8) & 0xFF, ab =  a         & 0xFF;
        int ba = (b >> 24) & 0xFF, br = (b >> 16) & 0xFF,
                bg = (b >>  8) & 0xFF, bb =  b         & 0xFF;
        return ((int)(aa + (ba - aa) * t) << 24)
                | ((int)(ar + (br - ar) * t) << 16)
                | ((int)(ag + (bg - ag) * t) <<  8)
                |  (int)(ab + (bb - ab) * t);
    }

    private static String keyName(int kc) {
        if (kc < 0) return "NONE";
        try { return InputConstants.Type.KEYSYM.getOrCreate(kc)
                .getDisplayName().getString(); }
        catch (Exception e) { return "Key " + kc; }
    }

    public static ZenithScreen INSTANCE;
}