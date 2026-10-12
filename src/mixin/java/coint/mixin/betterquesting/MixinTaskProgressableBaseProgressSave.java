package coint.mixin.betterquesting;

import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import bq_standard.tasks.base.TaskBase;
import bq_standard.tasks.base.TaskProgressableBase;

/**
 * Saving one player's task progress scanned the progress of every user who ever started the task. Look the player up
 * directly; the written NBT is the same. The completion part goes through {@link MixinTaskBaseProgressSave}.
 */
@Mixin(value = TaskProgressableBase.class, remap = false)
public abstract class MixinTaskProgressableBaseProgressSave extends TaskBase {

    @Shadow
    @Final
    protected TreeMap<UUID, Object> userProgress;

    @Shadow
    public abstract void writeUserProgressToNBT(NBTTagCompound nbt, Object progress);

    @WrapMethod(method = "writeProgressToNBT")
    private NBTTagCompound cointcore$singleUserProgress(NBTTagCompound nbt, List<UUID> users,
        Operation<NBTTagCompound> original) {
        if (users == null || users.size() != 1 || users.get(0) == null) {
            return original.call(nbt, users);
        }
        UUID user = users.get(0);
        super.writeProgressToNBT(nbt, users);
        NBTTagList list = new NBTTagList();
        Object progress = userProgress.get(user);
        if (progress != null) {
            NBTTagCompound progressNBT = new NBTTagCompound();
            progressNBT.setString("uuid", user.toString());
            writeUserProgressToNBT(progressNBT, progress);
            list.appendTag(progressNBT);
        }
        nbt.setTag("userProgress", list);
        return nbt;
    }
}
