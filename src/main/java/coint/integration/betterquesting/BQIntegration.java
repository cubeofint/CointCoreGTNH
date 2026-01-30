package coint.integration.betterquesting;

import net.minecraftforge.common.MinecraftForge;

import coint.CointCore;
import coint.config.CointConfig;
import coint.integration.IIntegration;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;

/**
 * Integration with BetterQuesting mod.
 */
public class BQIntegration implements IIntegration {

    public static final String MOD_ID = "betterquesting";

    private BQEventListener eventListener;
    private PartyEventListener partyEventListener;

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
        // Register quest completion listener
        eventListener = new BQEventListener();
        MinecraftForge.EVENT_BUS.register(eventListener);
        CointCore.LOG.debug("Registered BQEventListener");

        // Register party event listener
        partyEventListener = new PartyEventListener();
        FMLCommonHandler.instance()
            .bus()
            .register(partyEventListener);
        CointCore.LOG.debug("Registered PartyEventListener");

        // Set up mixin callback for party join events
        if (CointConfig.syncNewPartyMembers) {
            PartyAccessor.setOnPlayerJoinPartyCallback((playerId, party) -> {
                CointCore.LOG.debug("Mixin callback: player {} joined party", playerId);
                partyEventListener.onPlayerJoinParty(playerId, party);
            });
            CointCore.LOG.debug("Registered party join mixin callback");
        }
    }

    @Override
    public String getName() {
        return "BetterQuesting Integration";
    }

    /**
     * Get the quest event listener (available after registration)
     */
    public BQEventListener getEventListener() {
        return eventListener;
    }

    /**
     * Get the party event listener (available after registration)
     */
    public PartyEventListener getPartyEventListener() {
        return partyEventListener;
    }
}
