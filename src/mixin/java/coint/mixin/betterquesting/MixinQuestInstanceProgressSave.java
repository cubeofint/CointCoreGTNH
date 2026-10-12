package coint.mixin.betterquesting;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import betterquesting.questing.QuestInstance;
import betterquesting.questing.tasks.TaskStorage;

/**
 * On every world save BetterQuesting writes each dirty player's progress separately, and for each of the ~3.5k
 * quests the original scans every user who ever completed it just to pick out that one player. With one user asked
 * for, look the entry up directly. The written NBT is the same.
 */
@Mixin(value = QuestInstance.class, remap = false)
public abstract class MixinQuestInstanceProgressSave {

    @Shadow
    @Final
    private HashMap<UUID, NBTTagCompound> completeUsers;

    @Shadow
    @Final
    private TaskStorage tasks;

    @WrapMethod(method = "writeProgressToNBT")
    private NBTTagCompound cointcore$singleUserProgress(NBTTagCompound json, List<UUID> users,
        Operation<NBTTagCompound> original) {
        if (users == null || users.size() != 1 || users.get(0) == null) {
            return original.call(json, users);
        }
        UUID user = users.get(0);
        synchronized (completeUsers) {
            NBTTagList comJson = new NBTTagList();
            NBTTagCompound entry = completeUsers.get(user);
            if (entry != null) {
                NBTTagCompound tags = (NBTTagCompound) entry.copy();
                tags.setString("uuid", user.toString());
                comJson.appendTag(tags);
            }
            json.setTag("completed", comJson);
            json.setTag("tasks", tasks.writeProgressToNBT(new NBTTagList(), users));
            return json;
        }
    }
}
