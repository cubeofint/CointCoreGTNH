package coint.mixin.betterquesting;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import betterquesting.api.enums.EnumPartyStatus;
import betterquesting.api.questing.party.IParty;
import betterquesting.questing.party.PartyInstance;
import coint.integration.betterquesting.PartyAccessor;

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
        // Notify the accessor about the status change
        PartyAccessor.onPlayerStatusChange(uuid, (IParty) this, priv);
    }
}
