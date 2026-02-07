package coint.core;

import coint.CointCore;
import coint.Tags;
import coint.config.CointConfig;
import coint.commands.CommandHeal;
import coint.commands.CommandRepair;
import coint.commands.CommandSync;
import coint.module.epochsync.EpochSyncModule;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
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

    /**
     * Called when server is starting
     */
    public void serverStarting(FMLServerStartingEvent event) {
        moduleManager.serverStarting();

        // Register commands
        event.registerServerCommand(new CommandSync());
        event.registerServerCommand(new CommandRepair());
        event.registerServerCommand(new CommandHeal());
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
