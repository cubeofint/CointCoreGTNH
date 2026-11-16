package coint.mixin.nei;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "codechicken.nei.NEIServerUtils", remap = false)
public class MixinNEIServerUtilsLinks {

    // Older NEI versions do not have bookmark sharing.
    @Inject(
        method = "sendChatItemLink",
        at = @At(
            value = "INVOKE",
            target = "Lcodechicken/core/ServerUtils;sendChatToAll(Lnet/minecraft/util/IChatComponent;)V"),
        require = 0)
    private static void cointcore$relayLink(EntityPlayerMP sender, NBTTagCompound payload, CallbackInfo ci) {
        coint.cluster.ClusterItemLinks.publish(sender, payload);
    }
}
