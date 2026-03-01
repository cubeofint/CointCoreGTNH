package coint.tasks;

import net.minecraft.entity.item.EntityItem;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;

import coint.util.ItemUtil;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public class DropHandler {

    @SubscribeEvent
    public void onItemDrop(EntityJoinWorldEvent event) {
        if (event.entity instanceof EntityItem item && !event.world.isRemote) {
            ItemUtil.setDropTag(item);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onItemPickup(EntityItemPickupEvent event) {
        ItemUtil.removeDropTag(event.item);
    }
}
