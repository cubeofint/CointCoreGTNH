package coint.commands.privilege;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import ic2.api.item.ElectricItem;
import ic2.api.item.IElectricItem;
import ic2.api.item.IElectricItemManager;
import ic2.api.item.ISpecialElectricItem;

/**
 * Fills the energy of items: IC2/GregTech EU items and RF items (Thermal, EnderIO). Like a charger would, only at
 * once.
 */
final class ItemCharger {

    enum Result {
        CHARGED,
        ALREADY_FULL,
        NO_STORAGE
    }

    private static final int FILL_GUARD = 64;

    private static boolean rfLooked;
    private static Class<?> rfItem;
    private static Method rfReceive, rfStored, rfMax;

    private ItemCharger() {}

    static Result charge(ItemStack stack) {
        if (stack == null || stack.getItem() == null || stack.stackSize != 1) {
            return Result.NO_STORAGE;
        }
        if (stack.getItem() instanceof IElectricItem electric) {
            return chargeEu(stack, electric);
        }
        return chargeRf(stack);
    }

    private static Result chargeEu(ItemStack stack, IElectricItem electric) {
        IElectricItemManager manager = electric instanceof ISpecialElectricItem special ? special.getManager(stack)
            : ElectricItem.manager;
        if (manager == null) {
            return Result.NO_STORAGE;
        }
        double max = electric.getMaxCharge(stack);
        if (max <= 0) {
            return Result.NO_STORAGE;
        }
        if (manager.getCharge(stack) >= max) {
            return Result.ALREADY_FULL;
        }
        boolean any = false;
        for (int i = 0; i < FILL_GUARD; i++) {
            double room = max - manager.getCharge(stack);
            if (room <= 0) {
                break;
            }
            double got = manager.charge(stack, room, Integer.MAX_VALUE, true, false);
            if (got <= 0) {
                break;
            }
            any = true;
        }
        return any ? Result.CHARGED : Result.ALREADY_FULL;
    }

    private static Result chargeRf(ItemStack stack) {
        if (!rfLooked) {
            rfLooked = true;
            try {
                rfItem = Class.forName("cofh.api.energy.IEnergyContainerItem");
                rfReceive = rfItem.getMethod("receiveEnergy", ItemStack.class, int.class, boolean.class);
                rfStored = rfItem.getMethod("getEnergyStored", ItemStack.class);
                rfMax = rfItem.getMethod("getMaxEnergyStored", ItemStack.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                rfItem = null;
            }
        }
        if (rfItem == null || !rfItem.isInstance(stack.getItem())) {
            return Result.NO_STORAGE;
        }
        try {
            Object item = stack.getItem();
            int max = (Integer) rfMax.invoke(item, stack);
            if (max <= 0) {
                return Result.NO_STORAGE;
            }
            if ((Integer) rfStored.invoke(item, stack) >= max) {
                return Result.ALREADY_FULL;
            }
            boolean any = false;
            for (int i = 0; i < FILL_GUARD; i++) {
                int room = max - (Integer) rfStored.invoke(item, stack);
                if (room <= 0) {
                    break;
                }
                int got = (Integer) rfReceive.invoke(item, stack, room, false);
                if (got <= 0) {
                    break;
                }
                any = true;
            }
            return any ? Result.CHARGED : Result.ALREADY_FULL;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Result.NO_STORAGE;
        }
    }

    /** Everything the player carries: inventory, armor and baubles. */
    static List<ItemStack> carried(EntityPlayer player) {
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack s : player.inventory.mainInventory) {
            if (s != null) {
                stacks.add(s);
            }
        }
        for (ItemStack s : player.inventory.armorInventory) {
            if (s != null) {
                stacks.add(s);
            }
        }
        IInventory baubles = baubles(player);
        if (baubles != null) {
            for (int i = 0; i < baubles.getSizeInventory(); i++) {
                ItemStack s = baubles.getStackInSlot(i);
                if (s != null) {
                    stacks.add(s);
                }
            }
        }
        return stacks;
    }

    private static boolean baublesLooked;
    private static Method getBaubles;

    private static IInventory baubles(EntityPlayer player) {
        if (!baublesLooked) {
            baublesLooked = true;
            try {
                getBaubles = Class.forName("baubles.api.BaublesApi")
                    .getMethod("getBaubles", EntityPlayer.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                getBaubles = null;
            }
        }
        if (getBaubles == null) {
            return null;
        }
        try {
            Object inv = getBaubles.invoke(null, player);
            return inv instanceof IInventory i ? i : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Baubles keep their own copy until told: after charging them it is saved. */
    static void markBaublesChanged(EntityPlayer player) {
        IInventory baubles = baubles(player);
        if (baubles != null) {
            baubles.markDirty();
        }
    }
}
