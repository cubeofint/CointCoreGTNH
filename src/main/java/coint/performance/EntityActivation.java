package coint.performance;

import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.boss.IBossDisplayData;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import coint.CointConfig;

public final class EntityActivation {

    private EntityActivation() {}

    public static boolean shouldRunFullTick(EntityLiving entity) {
        if (!CointConfig.limiter.aiThrottleEnabled) {
            return true;
        }

        World world = entity.worldObj;
        if (world == null || world.isRemote) {
            return true;
        }

        int interval = Math.max(1, CointConfig.limiter.aiThrottleInterval);
        if (interval <= 1) {
            return true;
        }

        if (entity instanceof IBossDisplayData || entity instanceof EntityVillager) {
            return true;
        }
        if (entity instanceof EntityTameable && ((EntityTameable) entity).isTamed()) {
            return true;
        }
        if (entity.getAttackTarget() != null || entity.getAITarget() != null) {
            return true;
        }
        if (entity.hurtTime > 0 || entity.attackTime > 0 || entity.isBurning()) {
            return true;
        }
        if (!entity.getActivePotionEffects()
            .isEmpty()) {
            return true;
        }
        if (entity.ridingEntity != null || entity.riddenByEntity != null || entity.getLeashed()) {
            return true;
        }
        if (entity.hasCustomNameTag()) {
            return true;
        }

        double distance = Math.max(16, CointConfig.limiter.aiFullSpeedDistance);
        double maxDistanceSq = distance * distance;

        for (Object value : world.playerEntities) {
            if (!(value instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) value;
            if (player.isDead) {
                continue;
            }

            double dx = entity.posX - player.posX;
            double dy = entity.posY - player.posY;
            double dz = entity.posZ - player.posZ;
            if (dx * dx + dy * dy + dz * dz <= maxDistanceSq) {
                return true;
            }
        }

        long phase = world.getTotalWorldTime() + entity.getEntityId();
        return Math.floorMod(phase, interval) == 0;
    }
}
