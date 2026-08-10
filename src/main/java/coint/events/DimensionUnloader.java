package coint.events;

import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

@EventBusSubscriber
public class DimensionUnloader {

    private static final int STARTUP_DELAY_TICKS = 200;

    @EventBusSubscriber.Condition
    public static boolean isEnabled() {
        return CointConfig.general.unloadEmptyDimensions;
    }

    private static boolean started = false;
    private static boolean pending = true;
    private static int ticksLeft = STARTUP_DELAY_TICKS;

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (!pending || event.phase != TickEvent.Phase.END) return;
        if (!started) {
            started = true;
            CointCore.LOG.info("Dimension unloading delayed for {} seconds", STARTUP_DELAY_TICKS / 20);
        }
        if (--ticksLeft > 0) return;
        pending = false;
        var unloaded = unloadEmptyDimensions();
        CointCore.LOG.info("Queued {} empty dimension(s) for unload", unloaded);
    }

    public static int unloadEmptyDimensions() {
        int unloaded = 0;
        for (WorldServer world : DimensionManager.getWorlds()) {
            int dim = world.provider.dimensionId;
            if (dim == 0 || dim == -1) continue;
            if (DimensionManager.shouldLoadSpawn(dim)) continue;
            if (!world.playerEntities.isEmpty()) continue;
            if (world.theChunkProviderServer.loadedChunks.size() > 0) continue;
            if (!ForgeChunkManager.getPersistentChunksFor(world)
                .isEmpty()) continue;

            DimensionManager.unloadWorld(dim);
            unloaded++;
        }

        return unloaded;
    }
}
