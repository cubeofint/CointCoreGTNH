package coint.mixin.serverutilities;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.integration.serverutilities.CointRankConfigs;
import serverutils.ServerUtilitiesPermissions;
import serverutils.data.ClaimedChunks;
import serverutils.data.ServerUtilitiesTeamData;
import serverutils.lib.data.ForgePlayer;

/**
 * Replaces ServerUtilities' sum-based chunk limit logic with max-based logic for both
 * claim chunks and force-load chunks, and adds per-rank bonuses on top.
 *
 * <p>
 * Formula for both methods:
 * 
 * <pre>
 *   limit = MAX(base_permission per member) + MAX(cointcore.bonus_* per member)
 * </pre>
 *
 * <p>
 * The result is cached in the original private fields so SU's own cache
 * invalidation via {@code clearCache()} continues to work correctly.
 */
@Mixin(value = ServerUtilitiesTeamData.class, remap = false)
public class MixinServerUtilitiesTeamData {

    @Shadow
    private int cachedMaxClaimChunks;

    @Shadow
    private int cachedMaxChunkloaderChunks;

    /**
     * Completely replaces {@code getMaxClaimChunks()} with max-based logic.
     * Injects at HEAD and cancels the original to avoid the original sum loop.
     */
    @Inject(method = "getMaxClaimChunks", at = @At("HEAD"), cancellable = true)
    private void cointcore$maxClaimChunks(CallbackInfoReturnable<Integer> cir) {
        if (!ClaimedChunks.isActive()) {
            cir.setReturnValue(-1);
            return;
        }

        ServerUtilitiesTeamData self = (ServerUtilitiesTeamData) (Object) this;
        if (!self.team.isValid()) {
            cir.setReturnValue(-2);
            return;
        }

        if (cachedMaxClaimChunks >= 0) {
            cir.setReturnValue(cachedMaxClaimChunks);
            return;
        }

        int maxBase = 0;
        int maxBonus = 0;
        for (ForgePlayer player : self.team.getMembers()) {
            maxBase = Math.max(
                maxBase,
                player.getRankConfig(ServerUtilitiesPermissions.CLAIMS_MAX_CHUNKS)
                    .getInt());
            maxBonus = Math.max(
                maxBonus,
                player.getRankConfig(CointRankConfigs.BONUS_CLAIM_CHUNKS)
                    .getInt());
        }

        cachedMaxClaimChunks = maxBase + maxBonus;
        cir.setReturnValue(cachedMaxClaimChunks);
    }

    /**
     * Completely replaces {@code getMaxChunkloaderChunks()} with max-based logic,
     * adding {@code cointcore.bonus_forceload_chunks} on top.
     */
    @Inject(method = "getMaxChunkloaderChunks", at = @At("HEAD"), cancellable = true)
    private void cointcore$maxChunkloaderChunks(CallbackInfoReturnable<Integer> cir) {
        if (!ClaimedChunks.isActive()) {
            cir.setReturnValue(-1);
            return;
        }

        ServerUtilitiesTeamData self = (ServerUtilitiesTeamData) (Object) this;
        if (!self.team.isValid()) {
            cir.setReturnValue(-2);
            return;
        }

        if (cachedMaxChunkloaderChunks >= 0) {
            cir.setReturnValue(cachedMaxChunkloaderChunks);
            return;
        }

        int maxBase = 0;
        int maxBonus = 0;
        for (ForgePlayer player : self.team.getMembers()) {
            maxBase = Math.max(
                maxBase,
                player.getRankConfig(ServerUtilitiesPermissions.CHUNKLOADER_MAX_CHUNKS)
                    .getInt());
            maxBonus = Math.max(
                maxBonus,
                player.getRankConfig(CointRankConfigs.BONUS_FORCELOAD_CHUNKS)
                    .getInt());
        }

        cachedMaxChunkloaderChunks = maxBase + maxBonus;
        cir.setReturnValue(cachedMaxChunkloaderChunks);
    }
}
