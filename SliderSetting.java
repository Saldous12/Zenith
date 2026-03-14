package com.zenith.module;

import com.mojang.blaze3d.platform.InputConstants;
import com.zenith.category.Category;
import com.zenith.modules.client.HUD;
import com.zenith.modules.combat.KillAura;
import com.zenith.modules.donut.AutoEat;
import com.zenith.modules.render.FullBright;
import com.zenith.modules.visual.PlayerESP;
import net.minecraft.client.Minecraft;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Singleton registry for all {@link Module} instances.
 *
 * Call {@link #init()} once at startup, then call {@link #onTick(Minecraft)}
 * every game tick from a {@code ClientTickEvents.END_CLIENT_TICK} listener.
 */
public class ModuleManager {

    private static ModuleManager instance;

    private final List<Module>         allModules       = new ArrayList<>();
    private final Map<Module, Boolean> keybindLastState = new HashMap<>();

    // ── Singleton ─────────────────────────────────────────────────────────────

    public static ModuleManager getInstance() {
        return instance;
    }

    // ── Startup ───────────────────────────────────────────────────────────────

    /**
     * Registers all modules and calls {@link Module#onInit()} on each.
     * Must be called once during {@code onInitializeClient}.
     */
    public void init() {
        instance = this;

        register(new HUD());
        register(new KillAura());
        register(new AutoEat());
        register(new FullBright());
        register(new PlayerESP());

        for (Module module : allModules) {
            module.onInit();
        }
    }

    private void register(Module module) {
        allModules.add(module);
    }

    // ── Per-tick ──────────────────────────────────────────────────────────────

    /**
     * Must be called every Minecraft client tick.
     * Handles keybind toggling and dispatches {@link Module#onTick()}.
     */
    public void onTick(Minecraft mc) {
        if (mc.player == null) return;

        // Edge-trigger keybind toggle — fire once on key press
        for (Module module : allModules) {
            int boundKey = module.getKeyCode();
            if (boundKey == -1) continue;

            boolean isDown  = InputConstants.isKeyDown(mc.getWindow(), boundKey);
            boolean wasDown = keybindLastState.getOrDefault(module, false);
            if (isDown && !wasDown) module.toggle();
            keybindLastState.put(module, isDown);
        }

        // Tick enabled modules
        for (Module module : allModules) {
            if (!module.isEnabled()) continue;
            try {
                module.onTick();
            } catch (Exception ex) {
                // Log but don't crash the game for a bad module
                ex.printStackTrace();
            }
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public List<Module> getModules() {
        return Collections.unmodifiableList(allModules);
    }

    public List<Module> getModulesByCategory(Category category) {
        return allModules.stream()
                .filter(m -> m.getCategory() == category)
                .sorted(Comparator.comparing(Module::getName))
                .collect(Collectors.toList());
    }

    public int getEnabledCount() {
        return (int) allModules.stream().filter(Module::isEnabled).count();
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T getModule(Class<T> type) {
        return allModules.stream()
                .filter(m -> type.isAssignableFrom(m.getClass()))
                .map(m -> (T) m)
                .findFirst()
                .orElse(null);
    }
}
