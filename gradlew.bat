package com.zenith.modules.donut;

import com.zenith.category.Category;
import com.zenith.module.Module;
import com.zenith.setting.SliderSetting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;

/**
 * DONUT → AutoEat
 * Automatically eats the first food item found in the hotbar
 * when hunger drops below the configured threshold.
 */
public class AutoEat extends Module {

    private final SliderSetting threshold =
            new SliderSetting("Threshold", 16.0, 1.0, 20.0);

    /** Cached reflection field for {@code Inventory.selected} (private in 1.21.11). */
    private static Field selectedField;

    static {
        try {
            selectedField = Inventory.class.getDeclaredField("selected");
            selectedField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            // field might be public or named differently — will fall back in setSelected()
        }
    }

    public AutoEat() {
        super("AutoEat", Category.DONUT);
        settings.add(threshold);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;
        if (mc.screen != null) return;

        int hunger = mc.player.getFoodData().getFoodLevel();
        // getValue() returns Double — use intValue() to avoid invalid cast
        if (hunger >= threshold.getValue().intValue()) return;

        // Search hotbar for food
        var inventory = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.has(DataComponents.FOOD)) {
                setSelected(inventory, i);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                break;
            }
        }
    }

    /** Sets the hotbar selection, handling the field being private in 1.21.11. */
    private static void setSelected(Inventory inventory, int slot) {
        // Try reflection first (handles private field in 1.21.11)
        if (selectedField != null) {
            try {
                selectedField.setInt(inventory, slot);
                return;
            } catch (Exception ignored) {}
        }
        // Fallback: try direct public field access (older MC builds)
        try {
            Field f = Inventory.class.getField("selected");
            f.setInt(inventory, slot);
        } catch (Exception ignored) {
            // If all else fails, nothing we can do without a mixin
        }
    }
}
