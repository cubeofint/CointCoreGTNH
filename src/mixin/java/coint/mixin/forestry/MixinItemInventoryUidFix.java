package coint.mixin.forestry;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagString;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/**
 * Fixes Forestry backpack duplication caused by broken parent-item identification.
 *
 * <p>
 * In some Forestry builds the UID is written as an int tag, but later compared as a string.
 * That breaks "is this the same parent ItemStack?" checks, preventing the hotbar slot from
 * being replaced with SlotLocked and allowing external GUI hotkeys to clone+drop the stack.
 */
@Pseudo
@Mixin(targets = "forestry.core.inventory.ItemInventory", remap = false)
public abstract class MixinItemInventoryUidFix {

    @Unique
    private static final String KEY_UID = "UID";

    @Overwrite
    private static boolean isSameItemInventory(ItemStack base, ItemStack comparison) {
        if (base == null || comparison == null) {
            return false;
        }
        if (base.getItem() != comparison.getItem()) {
            return false;
        }
        if (!base.hasTagCompound() || !comparison.hasTagCompound()) {
            return false;
        }

        Integer baseUid = cointcore$readUid(base.getTagCompound());
        Integer comparisonUid = cointcore$readUid(comparison.getTagCompound());
        return baseUid != null && baseUid.equals(comparisonUid);
    }

    @Unique
    private static Integer cointcore$readUid(NBTTagCompound nbt) {
        if (nbt == null || !nbt.hasKey(KEY_UID)) {
            return null;
        }

        NBTBase tag = nbt.getTag(KEY_UID);
        if (tag instanceof NBTTagInt) {
            return nbt.getInteger(KEY_UID);
        }
        if (tag instanceof NBTTagString) {
            String s = nbt.getString(KEY_UID);
            if (s == null || s.isEmpty()) {
                return null;
            }
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
