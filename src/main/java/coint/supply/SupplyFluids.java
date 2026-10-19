package coint.supply;

import java.lang.reflect.Method;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidContainerItem;

/**
 * Fluids on the filters of the supply buffer. A fluid filter is kept as a fluid display item (GregTech's drop, what NEI
 * shows for a fluid), so it looks like the fluid and says which it is; a container (bucket, cell) on a filter is the
 * container itself, unless set with Ctrl.
 */
public final class SupplyFluids {

    /** Without GregTech's display items: the fluid kept in the filter item's NBT under this key. */
    private static final String MARK = "CointSupplyFluid";

    private static Method fromDisplay, toDisplay;
    private static boolean looked;

    private SupplyFluids() {}

    /** The fluid a fluid filter item stands for, or null for an item filter. */
    public static FluidStack filterFluid(ItemStack item) {
        if (item == null) {
            return null;
        }
        if (item.hasTagCompound() && item.getTagCompound()
            .hasKey(MARK)) {
            return one(
                FluidStack.loadFluidStackFromNBT(
                    item.getTagCompound()
                        .getCompoundTag(MARK)));
        }
        return one(fromGregTechDisplay(item));
    }

    /** The fluid inside a container item (bucket, cell, tank item), or null. */
    public static FluidStack containerFluid(ItemStack item) {
        if (item == null) {
            return null;
        }
        FluidStack fluid = FluidContainerRegistry.getFluidForFilledItem(item);
        if (fluid == null && item.getItem() instanceof IFluidContainerItem container) {
            fluid = container.getFluid(item);
        }
        return one(fluid);
    }

    /** The item a fluid filter keeps for {@code fluid}: GregTech's display item, or {@code container} marked. */
    public static ItemStack filterItem(FluidStack fluid, ItemStack container) {
        lookUp();
        if (toDisplay != null) {
            try {
                ItemStack display = (ItemStack) toDisplay.invoke(null, fluid, false);
                if (display != null) {
                    display.stackSize = 1;
                    return display;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Marked container below.
            }
        }
        ItemStack marked = container.copy();
        marked.stackSize = 1;
        NBTTagCompound tag = marked.hasTagCompound() ? marked.getTagCompound() : new NBTTagCompound();
        tag.setTag(MARK, fluid.writeToNBT(new NBTTagCompound()));
        marked.setTagCompound(tag);
        return marked;
    }

    private static FluidStack fromGregTechDisplay(ItemStack item) {
        lookUp();
        if (fromDisplay == null) {
            return null;
        }
        try {
            return (FluidStack) fromDisplay.invoke(null, item);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void lookUp() {
        if (looked) {
            return;
        }
        looked = true;
        try {
            Class<?> utility = Class.forName("gregtech.api.util.GTUtility");
            fromDisplay = utility.getMethod("getFluidFromDisplayStack", ItemStack.class);
            toDisplay = utility.getMethod("getFluidDisplayStack", FluidStack.class, boolean.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            fromDisplay = toDisplay = null;
        }
    }

    private static FluidStack one(FluidStack fluid) {
        if (fluid == null || fluid.getFluid() == null) {
            return null;
        }
        FluidStack one = fluid.copy();
        one.amount = 1;
        return one;
    }
}
