package coint.core;

import net.minecraftforge.common.MinecraftForge;

import coint.CointCore;
import coint.Tags;
import coint.commands.*;
import coint.commands.chat.ChatSplitHandler;
import coint.commands.chat.CommandLocalSpy;
import coint.commands.dm.CommandDmSpy;
import coint.commands.dm.DmLogger;
import coint.commands.temprank.TempRankManager;
import coint.commands.temprank.TempRankTask;
import coint.config.CointConfig;
import coint.events.KeepInventoryHandler;
import coint.integration.galacticraft.GalacticraftGodHandler;
import coint.integration.serverutilities.*;
import coint.module.epochsync.EpochRegistry;
import coint.module.epochsync.EpochSyncModule;
import coint.tasks.CleanupTask;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.*;
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

        MinecraftForge.EVENT_BUS.register(new CointRankConfigs());
        // MinecraftForge.EVENT_BUS.register(new KeepInventoryHandler());
        MinecraftForge.EVENT_BUS.register(new ChatSplitHandler());
        // MinecraftForge.EVENT_BUS.register(new ChatMessageFilter());
        // MinecraftForge.EVENT_BUS.register(new TBanHandler());
        // FMLCommonHandler.instance().bus().register(new TBanFMLHandler());
        MinecraftForge.EVENT_BUS.register(new GalacticraftGodHandler());

        // Guard /fly, /god, /tpl "target another player" variants.
        // Must be registered here (not inside SUIntegration.register) because
        // moduleManager.postInit() is never invoked and SUIntegration.register()
        // would therefore never run.
        if (Loader.isModLoaded(SUIntegration.MOD_ID)) {
            MinecraftForge.EVENT_BUS.register(new CointCommandGuard());
        }

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
        event.registerServerCommand(new CommandKit(event.getServer()));
        event.registerServerCommand(new CommandNightVision());
        event.registerServerCommand(new CommandTpAlias());
        event.registerServerCommand(new CommandWarn());
        event.registerServerCommand(new CommandMute());
        event.registerServerCommand(new CommandUnmute());
        event.registerServerCommand(new CommandTBan());
        event.registerServerCommand(new CommandTRank());
        event.registerServerCommand(new CommandReload());
        event.registerServerCommand(new CommandReply());
        event.registerServerCommand(new CommandDmSpy());
        event.registerServerCommand(new CommandLocalSpy());
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
