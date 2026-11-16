package coint.mixin.nei;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IChatComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

@Pseudo
@Mixin(targets = "codechicken.nei.NEIServerUtils", remap = false)
public class MixinNEIServerUtilsLinks {

    // Older NEI versions do not have bookmark sharing.
    @WrapOperation(
        method = "sendChatItemLink",
        at = @At(
            value = "INVOKE",
            target = "Lcodechicken/core/ServerUtils;sendChatToAll(Lnet/minecraft/util/IChatComponent;)V"),
        require = 0)
    private static void cointcore$relayLink(IChatComponent component, Operation<Void> original,
        @Local(argsOnly = true) EntityPlayerMP sender, @Local(argsOnly = true) NBTTagCompound payload) {
        coint.cluster.ClusterItemLinks.publish(sender, payload);
        coint.chat.ChatChannels.broadcast(coint.chat.ChatChannel.GLOBAL, component);
    }
}
