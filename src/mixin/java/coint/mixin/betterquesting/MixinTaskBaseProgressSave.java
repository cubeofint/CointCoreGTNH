package coint.mixin.betterquesting;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import bq_standard.tasks.base.TaskBase;

/**
 * Same as {@link MixinQuestInstanceProgressSave} for task completion: saving one player's progress scanned every user
 * who ever completed the task. Look the player up directly; the written NBT is the same.
 */
@Mixin(value = TaskBase.class, remap = false)
public abstract class MixinTaskBaseProgressSave {

    @Shadow
    @Final
    protected Set<UUID> completeUsers;

    @WrapMethod(method = "writeProgressToNBT")
    private NBTTagCompound cointcore$singleUserCompletion(NBTTagCompound nbt, List<UUID> users,
        Operation<NBTTagCompound> original) {
        if (users == null || users.size() != 1 || users.get(0) == null) {
            return original.call(nbt, users);
        }
        UUID user = users.get(0);
        NBTTagList list = new NBTTagList();
        if (completeUsers.contains(user)) {
            list.appendTag(new NBTTagString(user.toString()));
        }
        nbt.setTag("completeUsers", list);
        return nbt;
    }
}
