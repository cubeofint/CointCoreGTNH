package coint.util;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/*
 * item utils
 */
public final class ItemUtil {

    private static final Logger LOG = LogManager.getLogger(ItemUtil.class);

    // In: itemstack to repair
    public static void Repair(ItemStack itemStack) {
        if (itemStack == null) return;
        // repairing for GT tools
        if (itemStack.hasTagCompound()) {
            NBTTagCompound root = itemStack.getTagCompound();
            if (root.hasKey("GT.ToolStats")) {
                NBTTagCompound nbt = root.getCompoundTag("GT.ToolStats");
                nbt.setLong("Damage", 0);
                long charge = nbt.getLong("MaxCharge");
                root.setLong("GT.ItemCharge", charge);
                LOG.debug("repaired gt tool");
            }

            if (root.hasKey("InfiTool")) {
                NBTTagCompound nbt = root.getCompoundTag("InfiTool");
                nbt.setLong("Damage", 0);
                LOG.debug("repaired tc tool");
            }

            if (root.hasKey("enderio.darksteel.upgrade.energyUpgrade")) {
                itemStack.setItemDamage(0);
                NBTTagCompound nbt = root.getCompoundTag("enderio.darksteel.upgrade.energyUpgrade");
                long charge = nbt.getLong("capacity");
                nbt.setLong("energy", charge);
                LOG.debug("repaired enderIO pickaxe");
            }

            if (chargeFromKnownKeys(root)) {
                LOG.debug("charged energy item");
            }
        } else if (itemStack.isItemDamaged()) {
            // repairing for simple tools
            LOG.debug("repaired simple tool");
            itemStack.setItemDamage(0);
        }
        LOG.debug("repaired null");
    }

    private static boolean chargeFromKnownKeys(NBTTagCompound nbt) {
        boolean updated = false;
        updated |= setNumericToMax(nbt, "charge", "maxCharge");
        updated |= setNumericToMax(nbt, "Charge", "MaxCharge");
        updated |= setNumericToMax(nbt, "energy", "maxEnergy");
        updated |= setNumericToMax(nbt, "Energy", "MaxEnergy");
        updated |= setNumericToMax(nbt, "energy", "capacity");
        updated |= setNumericToMax(nbt, "Energy", "Capacity");
        updated |= setNumericToMax(nbt, "energyStored", "maxEnergyStored");
        updated |= setNumericToMax(nbt, "EnergyStored", "MaxEnergyStored");
        updated |= setNumericToMax(nbt, "StoredEnergy", "MaxEnergy");
        updated |= setNumericToMax(nbt, "RF", "MaxRF");
        return updated;
    }

    private static boolean setNumericToMax(NBTTagCompound nbt, String valueKey, String maxKey) {
        if (!nbt.hasKey(maxKey)) {
            return false;
        }

        NBTBase tag = nbt.getTag(maxKey);
        if (tag == null) {
            return false;
        }

        switch (tag.getId()) {
            case 1 -> nbt.setByte(valueKey, nbt.getByte(maxKey));
            case 2 -> nbt.setShort(valueKey, nbt.getShort(maxKey));
            case 3 -> nbt.setInteger(valueKey, nbt.getInteger(maxKey));
            case 4 -> nbt.setLong(valueKey, nbt.getLong(maxKey));
            case 5 -> nbt.setFloat(valueKey, nbt.getFloat(maxKey));
            case 6 -> nbt.setDouble(valueKey, nbt.getDouble(maxKey));
            default -> {
                return false;
            }
        }

        return true;
    }
}
