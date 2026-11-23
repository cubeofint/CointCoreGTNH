package coint.mixin.appliedenergistics2;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.data.IAEStack;
import appeng.me.storage.NetworkInventoryHandler;
import coint.diagnostics.AEImportWatch;

/** Diagnostic counters only; the original storage operation and its result are preserved. */
@Mixin(value = NetworkInventoryHandler.class, remap = false)
public abstract class MixinNetworkInventoryImportTrace {

    @Shadow
    @Final
    private List<IMEInventoryHandler<?>> priorityInventory;

    @WrapMethod(method = "injectItems")
    private IAEStack<?> cointcore$traceInjection(IAEStack<?> input, Actionable type, BaseActionSource source,
        Operation<IAEStack<?>> original) {
        AEImportWatch.Trace trace = AEImportWatch.current();
        if (trace == null) return original.call(input, type, source);
        int depth = trace.enterNetwork(true, type == Actionable.SIMULATE, priorityInventory.size());
        try {
            return original.call(input, type, source);
        } finally {
            trace.leaveNetwork(depth);
        }
    }

    @WrapMethod(method = "extractItems")
    private IAEStack<?> cointcore$traceExtraction(IAEStack<?> input, Actionable type, BaseActionSource source,
        Operation<IAEStack<?>> original) {
        AEImportWatch.Trace trace = AEImportWatch.current();
        if (trace == null) return original.call(input, type, source);
        int depth = trace.enterNetwork(false, type == Actionable.SIMULATE, priorityInventory.size());
        try {
            return original.call(input, type, source);
        } finally {
            trace.leaveNetwork(depth);
        }
    }
}
