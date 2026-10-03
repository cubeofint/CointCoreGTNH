package coint.mixin.appliedenergistics2;

import java.util.Queue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.hooks.TickHandler;
import coint.ae2.AEGridUnloadBatcher;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.gameevent.TickEvent.WorldTickEvent;

@Mixin(value = TickHandler.class, remap = false)
public abstract class MixinTickHandlerUnloadFlush {

    @Unique
    private static final long cointcore$readyQueueBudgetNanos = 4_000_000L;

    @Unique
    private long cointcore$readyQueueDeadlineNanos = Long.MAX_VALUE;

    @Inject(method = "onTick", at = @At("HEAD"))
    private void cointcore$onTickHead(TickEvent event, CallbackInfo ci) {
        if (event.type == TickEvent.Type.SERVER && event.phase == TickEvent.Phase.END) {
            cointcore$readyQueueDeadlineNanos = System.nanoTime() + cointcore$readyQueueBudgetNanos;
        }

        if (event.type == TickEvent.Type.WORLD && event.phase == TickEvent.Phase.END) {
            WorldTickEvent worldEvent = (WorldTickEvent) event;
            if (!worldEvent.world.isRemote) {
                AEGridUnloadBatcher.flush();
            }
        }
    }

    @Redirect(
        method = "onTick",
        at = @At(value = "INVOKE", target = "Ljava/util/Queue;isEmpty()Z", ordinal = 0),
        require = 1)
    private boolean cointcore$stopReadyQueueAtBudget(Queue<?> queue) {
        return queue.isEmpty() || System.nanoTime() >= cointcore$readyQueueDeadlineNanos;
    }
}
