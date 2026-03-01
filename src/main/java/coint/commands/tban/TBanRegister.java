package coint.commands.tban;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public class TBanRegister {

    @SubscribeEvent
    public void onEntityConstruct(EntityEvent.EntityConstructing event) {
        if (event.entity instanceof EntityPlayer player && !event.entity.worldObj.isRemote) {
            if (player.getExtendedProperties(PlayerTBanData.EXT_PROP) == null) {
                player.registerExtendedProperties(PlayerTBanData.EXT_PROP, new PlayerTBanData());
            }
        }
    }

    @SubscribeEvent
    public void playerClone(PlayerEvent.Clone event) {
        if (event.wasDeath) {
            PlayerTBanData originalData = PlayerTBanData.get(event.original);
            PlayerTBanData newData = PlayerTBanData.get(event.entityPlayer);
            if (originalData != null && newData != null) {
                NBTTagCompound compound = new NBTTagCompound();
                originalData.saveNBTData(compound);
                newData.loadNBTData(compound);
            }
        }
    }
}
