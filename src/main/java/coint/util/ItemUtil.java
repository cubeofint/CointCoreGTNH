package coint.util;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/*
* item utils
* */
public final class ItemUtil {

    private static final Logger LOG = LogManager.getLogger(ItemUtil.class);

    //In: itemstack to repair
    public static void Repair(ItemStack itemStack) {
        if (itemStack == null) return;
        //repairing for GT tools
        if (itemStack.hasTagCompound() && itemStack.getTagCompound()
            .hasKey("GT.ToolStats")) {
            NBTTagCompound nbt = itemStack.getTagCompound()
                .getCompoundTag("GT.ToolStats");
            nbt.setLong("Damage", 0);
            LOG.debug("repaired gt tool");
        //repairing for vanila like tools
        } else if (itemStack.isItemDamaged()) {
            LOG.debug("repaired vanila like tool");
            itemStack.setItemDamage(0);
        }
        LOG.debug("repaired null");
    }
}
