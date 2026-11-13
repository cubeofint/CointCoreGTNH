package coint.migration;

import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityInteractEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@EventBusSubscriber
public final class MigrationGuardEvents {

    private MigrationGuardEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void breaking(BlockEvent.BreakEvent event) {
        if (MigrationGuard.block(event.world, event.x, event.z)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void attack(AttackEntityEvent event) {
        if (MigrationGuard
            .block(event.target.worldObj, (int) Math.floor(event.target.posX), (int) Math.floor(event.target.posZ)))
            event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interact(PlayerInteractEvent event) {
        if (MigrationGuard.block(event.world, event.x, event.z)) {
            event.setCanceled(true);
            event.entityPlayer.addChatMessage(
                new net.minecraft.util.ChatComponentText("§eЭта территория заблокирована переносом команды."));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void entity(EntityInteractEvent event) {
        if (MigrationGuard
            .block(event.target.worldObj, (int) Math.floor(event.target.posX), (int) Math.floor(event.target.posZ)))
            event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void pickup(EntityItemPickupEvent event) {
        if (MigrationGuard
            .block(event.item.worldObj, (int) Math.floor(event.item.posX), (int) Math.floor(event.item.posZ)))
            event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void explosion(ExplosionEvent.Detonate event) {
        event.getAffectedBlocks()
            .removeIf(pos -> MigrationGuard.block(event.world, pos.chunkPosX, pos.chunkPosZ));
        event.getAffectedEntities()
            .removeIf(
                entity -> MigrationGuard
                    .block(entity.worldObj, (int) Math.floor(entity.posX), (int) Math.floor(entity.posZ)));
    }
}
