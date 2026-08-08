package coint.events;

import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@EventBusSubscriber
public class DimensionUnloader {

    @EventBusSubscriber.Condition
    public static boolean isEnabled() {
        return CointConfig.general.unloadEmptyDimensions;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStarted(FMLServerStartedEvent event) {
        unloadEmptyDimensions();
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

        if (unloaded > 0) {
            CointCore.LOG.info("Queued {} empty dimension(s) for unload", unloaded);
        }
        return unloaded;
    }
}
