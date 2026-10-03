package coint.mixin.appliedenergistics2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.api.networking.IGridConnection;
import appeng.me.GridConnection;
import appeng.me.GridNode;
import coint.ae2.AEGridUnloadBatcher;
import coint.ae2.GridNodeAccess;

@Mixin(value = GridConnection.class, remap = false)
public abstract class MixinGridConnectionBatchDestroy {

    @Shadow
    private GridNode sideA;

    @Shadow
    private GridNode sideB;

    @Inject(method = "destroy", at = @At("HEAD"), cancellable = true)
    private void cointcore$batchDestroy(CallbackInfo ci) {
        if (!AEGridUnloadBatcher.isBatching()) {
            return;
        }

        IGridConnection connection = (IGridConnection) (Object) this;
        ((GridNodeAccess) (Object) sideA).cointcore$removeConnection(connection);
        ((GridNodeAccess) (Object) sideB).cointcore$removeConnection(connection);
        AEGridUnloadBatcher.queue(sideA);
        AEGridUnloadBatcher.queue(sideB);
        ci.cancel();
    }
}
