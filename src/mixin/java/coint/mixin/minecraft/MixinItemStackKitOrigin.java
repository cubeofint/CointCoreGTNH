package coint.mixin.minecraft;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.commands.kit.KitMarkedStack;
import coint.commands.kit.KitOrigin;

@Mixin(ItemStack.class)
public abstract class MixinItemStackKitOrigin implements KitMarkedStack {

    @Shadow
    public NBTTagCompound stackTagCompound;

    @Unique
    private KitOrigin cointcore$kitOrigin;

    @Override
    public KitOrigin cointcore$getKitOrigin() {
        if (cointcore$kitOrigin == null) {
            cointcore$kitOrigin = KitOrigin.read(stackTagCompound);
        }
        if (cointcore$kitOrigin != null) {
            if (stackTagCompound == null) {
                stackTagCompound = new NBTTagCompound();
            }
            cointcore$kitOrigin.write(stackTagCompound);
        }
        return cointcore$kitOrigin;
    }

    @Override
    public void cointcore$setKitOrigin(KitOrigin origin) {
        cointcore$kitOrigin = origin;
        cointcore$getKitOrigin();
    }

    @Inject(method = { "getTagCompound", "hasTagCompound", "copy", "splitStack", "writeToNBT" }, at = @At("HEAD"))
    private void cointcore$repairKitTag(CallbackInfoReturnable<?> cir) {
        cointcore$getKitOrigin();
    }

    @Inject(method = "setTagCompound", at = @At("HEAD"))
    private void cointcore$rememberKitTag(NBTTagCompound tag, CallbackInfo ci) {
        cointcore$getKitOrigin();
    }

    @Inject(method = "setTagCompound", at = @At("RETURN"))
    private void cointcore$restoreKitTag(NBTTagCompound tag, CallbackInfo ci) {
        cointcore$getKitOrigin();
    }

    @Inject(method = { "copy", "splitStack" }, at = @At("RETURN"))
    private void cointcore$copyKitOrigin(CallbackInfoReturnable<ItemStack> cir) {
        KitOrigin origin = cointcore$getKitOrigin();
        if (origin != null) {
            ((KitMarkedStack) (Object) cir.getReturnValue()).cointcore$setKitOrigin(origin);
        }
    }

    @Inject(method = "writeToNBT", at = @At("RETURN"))
    private void cointcore$writeKitOrigin(NBTTagCompound tag, CallbackInfoReturnable<NBTTagCompound> cir) {
        KitOrigin origin = cointcore$getKitOrigin();
        if (origin != null) {
            origin.write(cir.getReturnValue());
        }
    }

    @Inject(method = "readFromNBT", at = @At("HEAD"))
    private void cointcore$rememberKitOrigin(NBTTagCompound tag, CallbackInfo ci) {
        cointcore$getKitOrigin();
        if (cointcore$kitOrigin == null) {
            cointcore$kitOrigin = KitOrigin.read(tag);
        }
    }

    @Inject(method = "readFromNBT", at = @At("RETURN"))
    private void cointcore$readKitOrigin(NBTTagCompound tag, CallbackInfo ci) {
        cointcore$getKitOrigin();
    }

    @Inject(method = "areItemStackTagsEqual", at = @At("HEAD"))
    private static void cointcore$compareKitOrigins(ItemStack left, ItemStack right,
        CallbackInfoReturnable<Boolean> cir) {
        KitOrigin.get(left);
        KitOrigin.get(right);
    }
}
