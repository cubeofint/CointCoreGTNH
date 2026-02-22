package coint.tasks;

import net.minecraft.entity.item.EntityItem;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import coint.util.ItemUtil;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public class DropHandler {

    @SubscribeEvent
    public void onPlayerDrop(EntityJoinWorldEvent event) {
        if (event.entity instanceof EntityItem item && !event.world.isRemote) {
            ItemUtil.setDropTime(item);
        }
    }
}
