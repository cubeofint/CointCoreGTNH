package coint;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import serverutils.lib.data.Universe;

@Mod(
    modid = CointCore.MOD_ID,
    version = CointCore.VERSION,
    name = CointCore.MOD_NAME,
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*", // Server-side only: client doesn't need this mod
    dependencies = "after:betterquesting;" + "after:serverutilities;" + "after:thaumcraft;")
public class CointCore {

    public static final String MOD_ID = "cointcore";
    public static final String VERSION = Tags.VERSION;
    public static final String MOD_NAME = "Coint Core GTNH";

    public static final Logger LOG = LogManager.getLogger(MOD_ID);

    // Server-side only - no client proxy needed
    public static final CommonProxy proxy = new CommonProxy();

    // ServerUtilities clears its static Universe instance before CointCore receives
    // FMLServerStoppingEvent, so keep the live object while the server is running.
    private Universe serverUtilitiesUniverse;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        boolean dbg = Boolean.getBoolean("cointcore.debug.forestryBackpackThrow");
        // WARN, чтобы было видно даже если INFO скрыт/уходит только в файл.
        LOG.warn("[CointCore] debug flag cointcore.debug.forestryBackpackThrow={}", dbg);
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        proxy.serverAboutToStart(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        // At this point ServerUtilities is fully started and Universe is valid.
        serverUtilitiesUniverse = Universe.get();
        proxy.serverStarted(event);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        // Because CointCore loads after ServerUtilities, ServerUtilities has already
        // set Universe.INSTANCE to null by the time this callback runs. Use the
        // object cached in serverStarted instead of calling Universe.get() here.
        Universe universe = serverUtilitiesUniverse;
        serverUtilitiesUniverse = null;

        if (universe == null) {
            LOG.warn("[DimensionCleaner] Skipping cleanup: cached ServerUtilities Universe is unavailable");
            return;
        }

        DimensionCleaner.get()
            .processDims(universe);
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        proxy.serverStopped(event);
    }
}
