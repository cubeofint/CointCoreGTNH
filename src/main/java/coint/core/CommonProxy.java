package coint.core;

import coint.commands.*;
import net.minecraftforge.common.MinecraftForge;

import coint.CointCore;
import coint.Tags;
import coint.commands.warn.WarnsRegister;
import coint.config.CointConfig;
import coint.integration.serverutilities.CointRankConfigs;
import coint.module.epochsync.EpochRegistry;
import coint.module.epochsync.EpochSyncModule;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

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
        MinecraftForge.EVENT_BUS.register(new WarnsRegister());

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
    public void init(FMLInitializationEvent event) {
        moduleManager.init();
    }

    /**
     * Called during FML postInit phase
     */
    public void postInit(FMLPostInitializationEvent event) {
        moduleManager.postInit();
        CointCore.LOG.info("CointCore GTNH initialized successfully");
    }

    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        EpochRegistry.init(event);
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
        event.registerServerCommand(new CommandWarn());
        CointCore.LOG.debug("Registered server commands");
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
    public ModuleManager getModuleManager() {
        return moduleManager;
    }
}
