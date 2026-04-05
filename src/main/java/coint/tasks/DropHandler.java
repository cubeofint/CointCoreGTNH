package coint.tasks;

import net.minecraft.entity.boss.IBossDisplayData;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.util.ItemUtil;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@EventBusSubscriber
public class DropHandler {

    // @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        if (event.entityItem != null) ItemUtil.setDropTag(event.entityItem.getEntityItem());
    }

    @SubscribeEvent
    public static void onLivingDrop(LivingDropsEvent event) {
        if (event.entity instanceof EntityPlayer || event.entity instanceof IBossDisplayData) {
            for (EntityItem drop : event.drops) {
                ItemUtil.setDropTag(drop.getEntityItem());
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onItemPickup(EntityItemPickupEvent event) {
        ItemUtil.removeDropTag(event.item.getEntityItem());
    }
}
