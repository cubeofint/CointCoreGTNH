package coint.commands.warn;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public class WarnsRegister {

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.entity instanceof EntityPlayer player && !event.entity.worldObj.isRemote) {
            if (player.getExtendedProperties(PlayerWarnsData.EXT_PROP) == null) {
                player.registerExtendedProperties(PlayerWarnsData.EXT_PROP, new PlayerWarnsData());
            }
        }
    }
}
