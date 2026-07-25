package coint.events;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.DamageSource;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@EventBusSubscriber
public class PDimVoidHandler {

    @EventBusSubscriber.Condition
    public static boolean isEnabled() {
        return CointConfig.general.pdimSaverEnabled;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onVoid(LivingDeathEvent event) {
        if ((event.entity instanceof EntityPlayerMP player) && player.worldObj.provider.dimensionId >= 180
            && event.source == DamageSource.outOfWorld) {
            event.setCanceled(true);

            var spawn = player.worldObj.getSpawnPoint();
            player.setPositionAndUpdate(spawn.posX, spawn.posY, spawn.posZ);

            player.setHealth(1);
            player.motionX = 0;
            player.motionY = 0;
            player.motionZ = 0;
            player.fallDistance = 0;
        }
    }
}
