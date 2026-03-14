package com.zenith.module;

import com.zenith.category.Category;
import com.zenith.setting.Setting;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class for all Zenith modules.
 *
 * <p>Subclasses override {@link #onEnable()}, {@link #onDisable()},
 * {@link #onTick()}, and optionally {@link #onInit()} to register
 * Fabric API event listeners that check {@link #isEnabled()} themselves.
 */
public abstract class Module {

    private final String   name;
    private final Category category;
    private boolean        enabled = false;
    private int            keyCode = -1;   // GLFW key code; -1 = unbound

    protected final List<Setting<?>> settings = new ArrayList<>();

    protected Module(String name, Category category) {
        this.name     = name;
        this.category = category;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Called by {@link ModuleManager} after all modules are registered. */
    public void onInit() {}

    /** Fired every Minecraft client tick when the module is enabled. */
    public void onTick() {}

    /** Called when the module is turned on. */
    public void onEnable() {}

    /** Called when the module is turned off. */
    public void onDisable() {}

    // ── Toggle ────────────────────────────────────────────────────────────────

    public final void toggle() {
        enabled = !enabled;
        if (enabled) onEnable();
        else         onDisable();
    }

    public final void setEnabled(boolean on) {
        if (on != enabled) toggle();
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String          getName()     { return name; }
    public Category        getCategory() { return category; }
    public boolean         isEnabled()   { return enabled; }
    public int             getKeyCode()  { return keyCode; }
    public void            setKeyCode(int code) { this.keyCode = code; }
    public List<Setting<?>> getSettings() { return settings; }
}
