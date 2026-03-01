package coint.commands.tban;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import coint.CointCore;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public class TBanHandler {

    @SubscribeEvent
    public void onEntityConstruct(EntityEvent.EntityConstructing event) {
        if (event.entity instanceof EntityPlayer player && !event.entity.worldObj.isRemote) {
            if (player.getExtendedProperties(PlayerTBanData.EXT_PROP) == null) {
                player.registerExtendedProperties(PlayerTBanData.EXT_PROP, new PlayerTBanData());
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onEntityJoin(EntityJoinWorldEvent event) {
        if (event.entity instanceof EntityPlayer player && !event.entity.worldObj.isRemote) {
            PlayerTBanData banData = PlayerTBanData.get(player);
            if (banData.isBanned()) {
                CointCore.LOG.info("Kicking banned player {}", player.getDisplayName());
                ((EntityPlayerMP) player).playerNetServerHandler.kickPlayerFromServer(
                    banData.get()
                        .getBanMessage());
            }
        }
    }

    @SubscribeEvent
    public void playerClone(PlayerEvent.Clone event) {
        if (event.wasDeath) {
            NBTTagCompound compound = new NBTTagCompound();
            PlayerTBanData.get(event.original)
                .saveNBTData(compound);
            PlayerTBanData.get(event.entityPlayer)
                .loadNBTData(compound);
        }
    }
}
