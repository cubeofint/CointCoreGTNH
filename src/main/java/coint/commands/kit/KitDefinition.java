package coint.commands.kit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

public class KitDefinition {

    private final String name;
    private final List<ItemStack> items;
    private final int maxClaims;

    public KitDefinition(String name, List<ItemStack> items, int maxClaims) {
        this.name = name;
        this.items = new ArrayList<>(items);
        this.maxClaims = maxClaims;
    }

    public String getName() {
        return name;
    }

    public List<ItemStack> getItems() {
        return Collections.unmodifiableList(items);
    }

    public int getMaxClaims() {
        return maxClaims;
    }
}
