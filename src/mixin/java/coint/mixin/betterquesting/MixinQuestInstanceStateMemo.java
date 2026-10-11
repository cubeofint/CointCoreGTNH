package coint.mixin.betterquesting;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import betterquesting.questing.QuestInstance;
import coint.performance.BQQuestStateMemo;

/**
 * Serves repeated {@code isUnlocked}/{@code isComplete} lookups from the memo while a quest cache rebuild runs.
 */
@Mixin(value = QuestInstance.class, remap = false)
public abstract class MixinQuestInstanceStateMemo {

    @WrapMethod(method = "isUnlocked")
    private boolean cointcore$memoUnlocked(UUID uuid, Operation<Boolean> original) {
        Boolean cached = BQQuestStateMemo.getUnlocked(this, uuid);
        if (cached != null) {
            return cached;
        }
        boolean result = original.call(uuid);
        BQQuestStateMemo.putUnlocked(this, uuid, result);
        return result;
    }

    @WrapMethod(method = "isComplete")
    private boolean cointcore$memoComplete(UUID uuid, Operation<Boolean> original) {
        Boolean cached = BQQuestStateMemo.getComplete(this, uuid);
        if (cached != null) {
            return cached;
        }
        boolean result = original.call(uuid);
        BQQuestStateMemo.putComplete(this, uuid, result);
        return result;
    }
}
