package coint.util;

import net.minecraft.item.ItemStack;
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
                LOG.debug("repaired gt tool");
            }

            if (root.hasKey("InfiTool")) {
                NBTTagCompound nbt = root.getCompoundTag("InfiTool");
                nbt.setLong("Damage", 0);
                LOG.debug("repaired tc tool");
            }

            if (root.hasKey("enderio.darksteel.upgrade.energyUpgrade")) {
                itemStack.setItemDamage(0);
                LOG.debug("repaired enderIO pickaxe");
            }

        } else if (itemStack.isItemDamaged()) {
            // repairing for simple tools
            LOG.debug("repaired simple tool");
            itemStack.setItemDamage(0);
        }
        LOG.debug("repaired null");
    }

}
