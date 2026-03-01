package coint.commands.warn;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public class WarnsHandler {

    @SubscribeEvent
    public void onEntityConstruct(EntityEvent.EntityConstructing event) {
        if (event.entity instanceof EntityPlayer player && !event.entity.worldObj.isRemote) {
            if (player.getExtendedProperties(PlayerWarnsData.EXT_PROP) == null) {
                player.registerExtendedProperties(PlayerWarnsData.EXT_PROP, new PlayerWarnsData());
            }
        }
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        if (event.wasDeath) {
            NBTTagCompound compound = new NBTTagCompound();
            PlayerWarnsData.get(event.original)
                .saveNBTData(compound);
            PlayerWarnsData.get(event.entityPlayer)
                .loadNBTData(compound);
        }
    }
}
