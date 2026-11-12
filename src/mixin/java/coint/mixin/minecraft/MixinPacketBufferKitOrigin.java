package coint.mixin.minecraft;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import coint.commands.kit.KitOrigin;

@Mixin(PacketBuffer.class)
public abstract class MixinPacketBufferKitOrigin {

    @WrapOperation(
        method = "writeItemStackToBuffer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/PacketBuffer;writeNBTTagCompoundToBuffer(Lnet/minecraft/nbt/NBTTagCompound;)V"))
    private void cointcore$shareKitOrigin(PacketBuffer buffer, NBTTagCompound tag, Operation<Void> original,
        ItemStack stack) {
        KitOrigin origin = KitOrigin.get(stack);
        if (origin != null) {
            // An item may deliberately hide all of its normal NBT from clients.
            if (tag == null) {
                tag = new NBTTagCompound();
            }
            origin.write(tag);
        }
        original.call(buffer, tag);
    }
}
