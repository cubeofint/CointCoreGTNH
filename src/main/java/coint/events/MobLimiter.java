package coint.events;

import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.INpc;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.IAnimals;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import cpw.mods.fml.common.eventhandler.Event.Result;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@EventBusSubscriber
public class MobLimiter {

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onCheckSpawn(LivingSpawnEvent.CheckSpawn event) {
        if (!CointConfig.limiter.enabled) return;

        double radius = CointConfig.limiter.radius;
        AxisAlignedBB narrow = box(event, radius);
        List<EntityLiving> nearby = event.world.getEntitiesWithinAABB(EntityLiving.class, box(event, radius * 2));

        if (nearby.size() >= CointConfig.limiter.totalCup) {
            event.setResult(Result.DENY);
            return;
        }

        int passive = 0;
        int hostile = 0;

        for (EntityLiving entity : nearby) {
            if (entity.boundingBox == null || !entity.boundingBox.intersectsWith(narrow)) continue;

            if (isPassive(entity) && ++passive >= CointConfig.limiter.passiveCup) {
                event.setResult(Result.DENY);
                return;
            }
            if (entity instanceof IMob && ++hostile >= CointConfig.limiter.hostileCup) {
                event.setResult(Result.DENY);
                return;
            }
        }
    }

    private static AxisAlignedBB box(LivingSpawnEvent.CheckSpawn event, double radius) {
        return AxisAlignedBB.getBoundingBox(
            event.x - radius,
            event.y - radius,
            event.z - radius,
            event.x + radius,
            event.y + radius,
            event.z + radius);
    }

    private static boolean isPassive(Entity entity) {
        return (entity instanceof IAnimals && !(entity instanceof IMob)) || entity instanceof INpc;
    }
}
