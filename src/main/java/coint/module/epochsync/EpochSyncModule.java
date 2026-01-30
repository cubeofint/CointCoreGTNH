package coint.module.epochsync;

import coint.CointCore;
import coint.config.CointConfig;
import coint.integration.betterquesting.BQIntegration;
import coint.integration.serverutilities.SUIntegration;
import coint.module.IModule;

/**
 * Module for synchronizing player epochs between BetterQuesting and ServerUtilities ranks.
 */
public class EpochSyncModule implements IModule {

    public static final String ID = "epochsync";
    public static final String NAME = "Epoch Sync";

    private final BQIntegration bqIntegration;
    private final SUIntegration suIntegration;

    public EpochSyncModule() {
        this.bqIntegration = new BQIntegration();
        this.suIntegration = new SUIntegration();
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void preInit() {
        CointCore.LOG.debug("EpochSync module preInit");
    }

    @Override
    public void init() {
        CointCore.LOG.debug("EpochSync module init");
    }

    @Override
    public void postInit() {
        if (!isEnabled()) {
            CointCore.LOG.info("EpochSync module is disabled");
            return;
        }

        // Register BetterQuesting integration
        if (bqIntegration.isAvailable()) {
            bqIntegration.register();
            CointCore.LOG.info("BetterQuesting integration enabled");
        } else {
            CointCore.LOG.warn("BetterQuesting not found, epoch sync will not work");
        }

        // Register ServerUtilities integration
        if (suIntegration.isAvailable()) {
            suIntegration.register();
            CointCore.LOG.info("ServerUtilities integration enabled");
        } else {
            CointCore.LOG.warn("ServerUtilities not found, rank management will not work");
        }
    }

    @Override
    public void serverStarting() {
        if (isEnabled() && suIntegration.isAvailable()) {
            suIntegration.registerCommands();
        }
    }

    @Override
    public boolean isEnabled() {
        return CointConfig.epochSyncEnabled;
    }

    /**
     * Get the BetterQuesting integration
     */
    public BQIntegration getBQIntegration() {
        return bqIntegration;
    }

    /**
     * Get the ServerUtilities integration
     */
    public SUIntegration getSUIntegration() {
        return suIntegration;
    }
}
