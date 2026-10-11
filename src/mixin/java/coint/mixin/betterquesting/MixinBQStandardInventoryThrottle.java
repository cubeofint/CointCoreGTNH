package coint.mixin.betterquesting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import bq_standard.handlers.EventHandler;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Retrieval tasks rescan every shared quest of a player whenever their inventory changes, which for active players is
 * almost every tick. Rescan each player at most once per {@link #INTERVAL} ticks; changes in between stay queued.
 */
@Mixin(value = EventHandler.class, remap = false)
public abstract class MixinBQStandardInventoryThrottle {

    @Unique
    private static final int INTERVAL = 10;
    @Unique
    private static final Map<EntityPlayer, Integer> cointcore$lastScan = new WeakHashMap<>();
    @Unique
    private static final List<EntityPlayer> cointcore$deferred = new ArrayList<>();

    @Shadow
    @Final
    private static HashSet<EntityPlayer> playerInventoryUpdates;

    @Inject(method = "onServerTick", at = @At("HEAD"))
    private void cointcore$deferRecentScans(TickEvent.ServerTickEvent event, CallbackInfo ci) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        int now = MinecraftServer.getServer()
            .getTickCounter();
        synchronized (playerInventoryUpdates) {
            Iterator<EntityPlayer> it = playerInventoryUpdates.iterator();
            while (it.hasNext()) {
                EntityPlayer player = it.next();
                Integer last = cointcore$lastScan.get(player);
                if (last != null && now - last >= 0 && now - last < INTERVAL) {
                    cointcore$deferred.add(player);
                    it.remove();
                } else {
                    cointcore$lastScan.put(player, now);
                }
            }
        }
    }

    @Inject(method = "onServerTick", at = @At("RETURN"))
    private void cointcore$requeueDeferred(TickEvent.ServerTickEvent event, CallbackInfo ci) {
        if (cointcore$deferred.isEmpty()) {
            return;
        }
        synchronized (playerInventoryUpdates) {
            playerInventoryUpdates.addAll(cointcore$deferred);
        }
        cointcore$deferred.clear();
    }
}
