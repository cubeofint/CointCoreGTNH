package coint.integration.serverutilities;

import coint.CointCore;
import coint.integration.IIntegration;
import cpw.mods.fml.common.Loader;

/**
 * Integration with ServerUtilities mod.
 */
public class SUIntegration implements IIntegration {

    public static final String MOD_ID = "serverutilities";

    @Override
    public String getModId() {
        return MOD_ID;
    }

    @Override
    public boolean isAvailable() {
        return Loader.isModLoaded(MOD_ID);
    }

    @Override
    public void register() {
        SURanksManager.init();
        CointCore.LOG.debug("ServerUtilities ranks manager initialized");
    }

    @Override
    public String getName() {
        return "ServerUtilities Integration";
    }

    /**
     * Register server commands (called during server starting)
     */
    public void registerCommands() {
        // Commands are registered via FMLServerStartingEvent in the proxy
        CointCore.LOG.debug("ServerUtilities commands ready for registration");
    }

    /**
     * Get the ranks manager instance
     */
    public SURanksManager getRanksManager() {
        return SURanksManager.getInstance();
    }
}
