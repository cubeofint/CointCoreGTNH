package coint.core;

import coint.CointCore;
import coint.Tags;
import coint.commands.CommandCleanup;
import coint.commands.CommandFeed;
import coint.commands.CommandHeal;
import coint.commands.CommandKit;
import coint.commands.CommandMute;
import coint.commands.CommandNightVision;
import coint.commands.CommandReload;
import coint.commands.CommandRepair;
import coint.commands.CommandReply;
import coint.commands.CommandSpy;
import coint.commands.CommandSync;
import coint.commands.CommandTBan;
import coint.commands.CommandTRank;
import coint.commands.CommandTpAlias;
import coint.commands.CommandUnmute;
import coint.commands.CommandWarn;
import coint.commands.spy.DmLogger;
import coint.commands.temprank.TempRankManager;
import coint.commands.temprank.TempRankTask;
import coint.config.CointConfig;
import coint.events.KeepInventoryHandler;
import coint.integration.serverutilities.CointSUPermissions;
import coint.integration.serverutilities.RanksManager;
import coint.integration.serverutilities.SUIntegration;
import coint.module.epochsync.EpochRegistry;
import coint.module.epochsync.EpochSyncModule;
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

    protected final ModuleManager moduleManager = new ModuleManager();

    /**
     * Called during FML preInit phase
     */
    public void preInit(FMLPreInitializationEvent event) {
        // Initialize configuration
        CointConfig.init(event.getSuggestedConfigurationFile());

        CointCore.LOG.info(CointConfig.greeting);
        CointCore.LOG.info("CointCore GTNH version {} initializing...", Tags.VERSION);

        // Register modules
        registerModules();

        // PreInit modules
        moduleManager.preInit();
    }

    /**
     * Called during FML init phase
     */
    @SuppressWarnings("unused")
    public void init(FMLInitializationEvent event) {
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

        moduleManager.init();
    }

    /**
     * Called during FML postInit phase
     */
    @SuppressWarnings("unused")
    public void postInit(FMLPostInitializationEvent event) {}

    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        EpochRegistry.init(event);
        moduleManager.onAboutToStart();
        DmLogger.init(new java.io.File("."));
        CointCore.LOG.info("CointCore GTNH initialized successfully");
    }

    /**
     * Called when server is starting
     */
    public void serverStarting(FMLServerStartingEvent event) {
        moduleManager.serverStarting();

        // Register commands
        event.registerServerCommand(new CommandSync());
        event.registerServerCommand(new CommandRepair());
        event.registerServerCommand(new CommandHeal());
        event.registerServerCommand(new CommandFeed());
        event.registerServerCommand(new CommandKit());
        event.registerServerCommand(new CommandNightVision());
        event.registerServerCommand(new CommandTpAlias());
        event.registerServerCommand(new CommandWarn());
        event.registerServerCommand(new CommandMute());
        event.registerServerCommand(new CommandUnmute());
        event.registerServerCommand(new CommandTBan());
        event.registerServerCommand(new CommandTRank());
        event.registerServerCommand(new CommandReload());
        event.registerServerCommand(new CommandReply());
        // event.registerServerCommand(new CommandDmSpy());
        event.registerServerCommand(new CommandSpy());
        event.registerServerCommand(new CommandCleanup());
        CointCore.LOG.debug("Registered server commands");
    }

    @SuppressWarnings("unused")
    public void serverStarted(FMLServerStartedEvent event) {
        if (!ServerUtilitiesConfig.tasks.cleanup.enabled) {
            Universe universe = Universe.get();
            universe.scheduleTask(new CleanupTask(), CointConfig.cleanupEnabled);
        }
        // Register epoch ranks into ServerUtilities now that both EpochRegistry and
        // Ranks.INSTANCE are guaranteed to be fully initialized.
        RanksManager.get()
            .updateRanks();

        // Restore active temp-rank assignments and start the expiry checker.
        if (Loader.isModLoaded(SUIntegration.MOD_ID)) {
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
    }

    /**
     * Register all modules
     */
    protected void registerModules() {
        moduleManager.registerModule(new EpochSyncModule());
    }

    /**
     * Get the module manager
     */
    @SuppressWarnings("unused")
    public ModuleManager getModuleManager() {
        return moduleManager;
    }
}
