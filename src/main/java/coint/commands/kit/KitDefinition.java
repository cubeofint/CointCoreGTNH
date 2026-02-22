package coint.commands.kit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

public class KitDefinition {

    private final String name;
    private final List<ItemStack> items;
    private final long cooldownTicks;

    public KitDefinition(String name, List<ItemStack> items, long cooldownTicks) {
        this.name = name;
        this.items = new ArrayList<>(items);
        this.cooldownTicks = cooldownTicks;
    }

    public String getName() {
        return name;
    }

    public List<ItemStack> getItems() {
        return Collections.unmodifiableList(items);
    }

    public long getCooldownTicks() {
        return cooldownTicks;
    }
}
