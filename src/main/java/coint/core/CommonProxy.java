package coint.core;

import coint.http.ChatWSClient;
import com.gtnewhorizon.gtnhlib.config.ConfigException;

import coint.CointCore;
import coint.Tags;
import coint.commands.CmdRegistry;
import coint.commands.spy.DmLogger;
import coint.commands.temprank.TempRankManager;
import coint.commands.temprank.TempRankTask;
import coint.config.CointConfig;
import coint.epochsync.EpochRegistry;
import coint.events.KeepInventoryHandler;
import coint.integration.serverutilities.CointSUPermissions;
import coint.integration.serverutilities.RanksManager;
import coint.tasks.CleanupTask;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import serverutils.ServerUtilitiesConfig;
import serverutils.lib.data.Universe;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

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
        // TODO: move to PermissionUtils
        PermissionAPI.registerNode(
            KeepInventoryHandler.PERMISSION,
            DefaultPermissionLevel.NONE,
            "Сохранять инвентарь при смерти");

        // Split /god, /fly, /tpl into self-use vs. targeting-others tiers.
        // The existing SU node "commands.<name>" controls self-use (unchanged).
        // These new nodes guard the "apply to another player" variant.
        PermissionAPI.registerNode(
            CointSUPermissions.TP_COORDS,
            DefaultPermissionLevel.NONE,
            "Teleport to coordinates via /tp x y z (JourneyMap waypoints)");
        PermissionAPI.registerNode(
            CointSUPermissions.GOD_OTHER,
            DefaultPermissionLevel.OP,
            "Apply god mode to another player via /god <player>");
        PermissionAPI.registerNode(
            CointSUPermissions.FLY_OTHER,
            DefaultPermissionLevel.OP,
            "Toggle fly for another player via /fly <player>");
        PermissionAPI.registerNode(
            CointSUPermissions.TPL_OTHER,
            DefaultPermissionLevel.OP,
            "Teleport another player to someone via /tpl <who> <to>");
        PermissionAPI.registerNode(
            CointSUPermissions.TPL_TO_PROTECTED,
            DefaultPermissionLevel.OP,
            "Teleport to protected players via /tpl (e.g. admins)");
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
        ChatWSClient.init();
        CmdRegistry.register(event);
    }

    @SuppressWarnings("unused")
    public void serverStarted(FMLServerStartedEvent event) {
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
    public void serverStopping(FMLServerStoppingEvent event) {
        DmLogger.close();
        ChatWSClient.inst.close();
    }
}
