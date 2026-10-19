package coint.supply;

import java.lang.reflect.Method;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidContainerItem;

/** The fluid an item stands for: in a container (bucket, cell, tank item) or a fluid shown by NEI. */
public final class SupplyFluids {

    private static Method fromDisplay;
    private static boolean looked;

    private SupplyFluids() {}

    /** One millibucket of the fluid {@code item} holds or shows, or null when it is not about a fluid. */
    public static FluidStack fluidOf(ItemStack item) {
        if (item == null) {
            return null;
        }
        FluidStack fluid = FluidContainerRegistry.getFluidForFilledItem(item);
        if (fluid == null && item.getItem() instanceof IFluidContainerItem container) {
            fluid = container.getFluid(item);
        }
        if (fluid == null) {
            fluid = fromGregTechDisplay(item);
        }
        if (fluid == null || fluid.getFluid() == null) {
            return null;
        }
        FluidStack one = fluid.copy();
        one.amount = 1;
        return one;
    }

    /** What NEI drags for a fluid in GregTech: a display item, read back by GregTech. */
    private static FluidStack fromGregTechDisplay(ItemStack item) {
        if (!looked) {
            looked = true;
            try {
                fromDisplay = Class.forName("gregtech.api.util.GTUtility")
                    .getMethod("getFluidFromDisplayStack", ItemStack.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                fromDisplay = null;
            }
        }
        if (fromDisplay == null) {
            return null;
        }
        try {
            return (FluidStack) fromDisplay.invoke(null, item);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static boolean sameFluid(FluidStack a, FluidStack b) {
        return a != null && b != null && a.isFluidEqual(b);
    }
}
