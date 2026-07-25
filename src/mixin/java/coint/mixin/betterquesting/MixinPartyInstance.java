package coint.mixin.betterquesting;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import betterquesting.api.enums.EnumPartyStatus;
import betterquesting.api.questing.party.IParty;
import betterquesting.questing.party.PartyInstance;
import coint.CointConfig;
import coint.CointCore;
import coint.epochsync.EpochEntry;
import coint.integration.serverutilities.RanksManager;

/**
 * Mixin for PartyInstance to intercept when players join a party.
 */
@Mixin(value = PartyInstance.class, remap = false)
public abstract class MixinPartyInstance implements IParty {

    /**
     * Called after setStatus to detect when a player is added to a party.
     */
    @Inject(method = "setStatus", at = @At("RETURN"))
    private void onSetStatus(UUID uuid, EnumPartyStatus priv, CallbackInfo ci) {
        if (!CointConfig.epochs.syncNewPartyMembers || !CointConfig.epochs.partySync) {
            return;
        }
        // Notify the accessor about the status change
        if (priv != null) {
            var mgr = RanksManager.get();
            EpochEntry partyEpoch = mgr.getHighestPartyEpoch(this);
            if (partyEpoch == null) {
                CointCore.LOG.debug("Party has no epoch rank, nothing to sync for new member {}", uuid);
                return;
            }

            if (mgr.needsEpochUpgrade(uuid, partyEpoch)) {
                CointCore.LOG.info("Syncing new party member {} to epoch: {}", uuid, partyEpoch.rankName);
                try {
                    RanksManager.get()
                        .setRank(uuid, partyEpoch.rankName);
                } catch (Exception e) {
                    CointCore.LOG
                        .error("Error setting rank {} for player {}: {}", partyEpoch.rankName, uuid, e.getMessage(), e);
                }
            }
        }
    }
}
