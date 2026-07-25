package coint;

import com.gtnewhorizon.gtnhlib.config.ConfigException;

import coint.commands.CmdRegistry;
import coint.commands.spy.DmLogger;
import coint.commands.temprank.TempRankManager;
import coint.commands.temprank.TempRankTask;
import coint.epochsync.EpochRegistry;
import coint.http.HubWebSocket;
import coint.integration.serverutilities.RanksManager;
import coint.util.PermissionsUtil;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import serverutils.ServerUtilitiesConfig;
import serverutils.lib.data.Universe;

/**
 * Common proxy for both client and server.
 */
public class CommonProxy {

    /**
     * Called during FML preInit phase
     */
    @SuppressWarnings("unused")
    public void preInit(FMLPreInitializationEvent event) {
        try {
            CointConfig.load();
        } catch (ConfigException e) {
            throw new RuntimeException(e);
        }

        CointCore.LOG.info("CointCore GTNH version {} initializing...", Tags.VERSION);
    }

    /**
     * Called during FML init phase
     */
    @SuppressWarnings("unused")
    public void init(FMLInitializationEvent event) {
        PermissionsUtil.register();
    }

    /**
     * Called during FML postInit phase
     */
    @SuppressWarnings("unused")
    public void postInit(FMLPostInitializationEvent event) {}

    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        EpochRegistry.init(event);
        DmLogger.init(new java.io.File("."));
        CointCore.LOG.info("CointCore GTNH initialized successfully");
    }

    /**
     * Called when server is starting
     */
    public void serverStarting(FMLServerStartingEvent event) {
        CmdRegistry.register(event);
    }

    @SuppressWarnings("unused")
    public void serverStarted(FMLServerStartedEvent event) {
        if (CointConfig.api.wsEnabled) {
            HubWebSocket.get();
        }
        if (!ServerUtilitiesConfig.tasks.cleanup.enabled) {
            Universe universe = Universe.get();
            universe.scheduleTask(new CleanupTask(), CointConfig.general.cleanupEnabled);
        }
        // Register epoch ranks into ServerUtilities now that both EpochRegistry and
        // Ranks.INSTANCE are guaranteed to be fully initialized.
        RanksManager.get()
            .updateRanks();

        // Restore active temp-rank assignments and start the expiry checker.
        if (Loader.isModLoaded("serverutilities")) {
            TempRankManager.reset(); // discard stale state from a previous session in this JVM
            TempRankManager.get()
                .restoreAll();
            Universe.get()
                .scheduleTask(new TempRankTask());
        }
    }

    @SuppressWarnings("unused")
    public void serverStopped(FMLServerStoppedEvent event) {
        DmLogger.close();
        if (CointConfig.api.wsEnabled) {
            HubWebSocket.get()
                .closeNormal("Перезагрузка");
        }
    }
}
