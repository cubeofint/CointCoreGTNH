package coint.mixin.appliedenergistics2;

import java.util.concurrent.Future;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.data.IAEStack;
import appeng.helpers.MultiCraftingTracker;

/**
 * Export buses and interfaces with a crafting card start a new crafting calculation on every operation while the
 * previous one failed (e.g. missing ingredients). Building each calculation rebuilds the network item list on the
 * server thread. Start a new calculation for a slot at most once per {@link #RETRY_TICKS}.
 */
@Mixin(value = MultiCraftingTracker.class, remap = false)
public abstract class MixinMultiCraftingTrackerRetry {

    @Unique
    private static final int RETRY_TICKS = 40;

    @Shadow
    @Final
    private int size;

    @Unique
    private int[] cointcore$nextJobTick;

    @Shadow
    private ICraftingLink getLink(int slot) {
        throw new AssertionError();
    }

    @Shadow
    private Future<ICraftingJob> getJob(int slot) {
        throw new AssertionError();
    }

    @Inject(
        method = "handleCrafting(IJLappeng/api/storage/data/IAEStack;Lnet/minecraft/world/World;Lappeng/api/networking/IGrid;Lappeng/api/networking/crafting/ICraftingGrid;Lappeng/api/networking/security/BaseActionSource;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0)
    private void cointcore$throttleNewJobs(int x, long itemToCraft, IAEStack<?> aes, World w, IGrid g, ICraftingGrid cg,
        BaseActionSource mySrc, CallbackInfoReturnable<Boolean> cir) {
        if (aes == null || x < 0 || x >= size || getJob(x) != null || getLink(x) != null) {
            return; // only gate the branch that would begin a new calculation
        }
        if (cointcore$nextJobTick == null) {
            cointcore$nextJobTick = new int[size];
        }
        int now = MinecraftServer.getServer()
            .getTickCounter();
        if (now - cointcore$nextJobTick[x] < 0) {
            cir.setReturnValue(false);
            return;
        }
        cointcore$nextJobTick[x] = now + RETRY_TICKS;
    }
}
