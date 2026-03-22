package coint.events;

import coint.config.CointConfig;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.INpc;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.IAnimals;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import java.util.List;

@EventBusSubscriber
public class MobLimiter {

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMobSpawn(EntityJoinWorldEvent event) {
        if (!CointConfig.limiterEnabled) return;
        if (event.entity instanceof EntityPlayer) return;

        int passive = 0;
        int hostile = 0;

        Chunk chunk = event.world.getChunkFromBlockCoords((int) event.entity.posX, (int) event.entity.posZ);
        for (List eList : chunk.entityLists) {
            for (Object obj : eList) {
                Entity entity;
                if (obj instanceof Entity e) {
                    entity = e;
                } else {
                    continue;
                }

                if (isPassive(entity)) {
                    passive++;
                    continue;
                }
                if (entity instanceof IMob) {
                    hostile++;
                }
            }
        }

        if (isPassive(event.entity) && passive >= CointConfig.limiterPassiveCup) {
            event.setCanceled(true);
            return;
        }
        if (event.entity instanceof IMob && hostile >= CointConfig.limiterHostileCup) {
            event.setCanceled(true);
        }
    }

    private static boolean isPassive(Entity entity) {
        return (entity instanceof IAnimals && !(entity instanceof IMob)) || entity instanceof INpc;
    }
}
