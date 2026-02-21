package coint.commands.mute;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public class MuteRegister {

    @SubscribeEvent
    public void onEntityConstruct(EntityEvent.EntityConstructing event) {
        if (event.entity instanceof EntityPlayer player && !event.entity.worldObj.isRemote) {
            if (player.getExtendedProperties(PlayerMuteData.EXT_PROP) == null) {
                player.registerExtendedProperties(PlayerMuteData.EXT_PROP, new PlayerMuteData());
            }
        }
    }

    @SubscribeEvent
    public void playerClone(PlayerEvent.Clone event) {
        if (event.wasDeath) {
            NBTTagCompound compound = new NBTTagCompound();
            PlayerMuteData.get(event.original)
                .saveNBTData(compound);
            PlayerMuteData.get(event.entityPlayer)
                .loadNBTData(compound);
        }
    }
}
