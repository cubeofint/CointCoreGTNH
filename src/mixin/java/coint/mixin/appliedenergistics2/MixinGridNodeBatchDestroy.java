package coint.mixin.appliedenergistics2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import appeng.api.networking.IGridConnection;
import appeng.me.GridNode;
import coint.ae2.GridNodeAccess;

@Mixin(value = GridNode.class, remap = false)
public abstract class MixinGridNodeBatchDestroy implements GridNodeAccess {

    @Override
    @Invoker("removeConnection")
    public abstract void cointcore$removeConnection(IGridConnection connection);

    @Override
    @Invoker("validateGrid")
    public abstract void cointcore$validateGrid();
}
