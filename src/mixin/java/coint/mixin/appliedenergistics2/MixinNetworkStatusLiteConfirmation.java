package coint.mixin.appliedenergistics2;

import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.container.implementations.ContainerNetworkStatus;
import appeng.me.cache.CraftingGridCache;
import coint.integration.appliedenergistics2.LiteCraftingMode;

@Mixin(value = ContainerNetworkStatus.class, remap = false)
public abstract class MixinNetworkStatusLiteConfirmation {

    @Shadow
    private CraftingGridCache getCraftingGridCache() {
        throw new AssertionError();
    }

    @Inject(method = "toggleLiteCraftingMode", at = @At("HEAD"), cancellable = true)
    private void cointcore$confirmDisable(CallbackInfo ci) {
        if (!LiteCraftingMode.isEndgame()) return;
        ContainerNetworkStatus container = (ContainerNetworkStatus) (Object) this;
        if (!(container.getInventoryPlayer().player instanceof EntityPlayerMP player)) return;
        ci.cancel();
        LiteCraftingMode.change(player, container, () -> {
            CraftingGridCache cache = getCraftingGridCache();
            if (cache == null) return;
            if (cache.getLiteCraftingDefault()) {
                LiteCraftingMode.confirm(
                    player,
                    container,
                    LiteCraftingMode.Scope.NETWORK,
                    () -> cointcore$setMode(container, cache, false));
            } else {
                cointcore$setMode(container, cache, true);
            }
        });
    }

    @Unique
    private static void cointcore$setMode(ContainerNetworkStatus container, CraftingGridCache cache, boolean enabled) {
        cache.setLiteCraftingDefault(enabled);
        container.liteCraftingDefault = enabled;
        container.detectAndSendChanges();
    }
}
