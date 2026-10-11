package coint.mixin.betterquesting;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import betterquesting.api.api.QuestingAPI;
import betterquesting.api2.cache.QuestCache;
import coint.performance.BQQuestStateMemo;

/**
 * Opens a quest state memo for the duration of one cache rebuild (see {@link BQQuestStateMemo}).
 */
@Mixin(value = QuestCache.class, remap = false)
public abstract class MixinQuestCacheUpdateMemo {

    @WrapMethod(method = "updateCache")
    private void cointcore$memoQuestState(EntityPlayer player, Operation<Void> original) {
        BQQuestStateMemo.begin(QuestingAPI.getQuestingUUID(player));
        try {
            original.call(player);
        } finally {
            BQQuestStateMemo.end();
        }
    }
}
