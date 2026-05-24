package coint.events;

import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.INpc;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.IAnimals;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.config.CointConfig;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@EventBusSubscriber
public class MobLimiter {

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMobSpawn(EntityJoinWorldEvent event) {
        if (event.entity instanceof EntityPlayer) return;
        if (!(event.entity instanceof EntityLiving)) return;
        if (!CointConfig.limiter.enabled) return;

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

                if (isPassive(entity) && passive++ >= CointConfig.limiter.passiveCup) {
                    event.setCanceled(true);
                    return;
                }
                if ((entity instanceof IMob) && hostile++ >= CointConfig.limiter.hostileCup) {
                    event.setCanceled(true);
                    return;
                }
                if (passive + hostile >= CointConfig.limiter.chunkCup) {
                    event.setCanceled(true);
                    return;
                }
            }
        }
    }

    private static boolean isPassive(Entity entity) {
        return (entity instanceof IAnimals && !(entity instanceof IMob)) || entity instanceof INpc;
    }
}
