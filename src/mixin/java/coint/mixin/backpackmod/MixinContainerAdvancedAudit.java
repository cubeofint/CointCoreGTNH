package coint.mixin.backpackmod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.debug.BackpackSecurityAudit;
import coint.debug.BackpackSecurityInspector;

@Pseudo
@Mixin(targets = "de.eydamos.backpack.inventory.container.ContainerAdvanced", remap = false)
public abstract class MixinContainerAdvancedAudit {

    @Unique
    private static Object cointcore$getBackpackSave(Object container) {
        try {
            Field f = container.getClass()
                .getDeclaredField("backpackSave");
            f.setAccessible(true);
            return f.get(container);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    private static String cointcore$getBackpackUid(Object container) {
        Object save = cointcore$getBackpackSave(container);
        if (save == null) return "null";
        try {
            Method m = save.getClass()
                .getMethod("getUUID");
            Object value = m.invoke(save);
            return value == null ? "null" : value.toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    @Inject(
        method = "slotClick(IIILnet/minecraft/entity/player/EntityPlayer;)Lnet/minecraft/item/ItemStack;",
        at = @At("HEAD"),
        remap = false,
        require = 0)
    private void cointcore$auditSlotClickHead(int slotId, int button, int mode, EntityPlayer player,
        CallbackInfoReturnable<ItemStack> cir) {
        String uid = cointcore$getBackpackUid(this);
        BackpackSecurityAudit.logEvent(
            "CONTAINER_SLOT_CLICK",
            "player=" + (player == null ? "null" : player.getCommandSenderName())
                + " uid="
                + uid
                + " slot="
                + slotId
                + " button="
                + button
                + " mode="
                + mode);
        BackpackSecurityInspector
            .onClick(player == null ? "null" : player.getCommandSenderName(), uid, slotId, button, mode, "container");
    }

    @Inject(
        method = "canInteractWith(Lnet/minecraft/entity/player/EntityPlayer;)Z",
        at = @At("RETURN"),
        remap = false,
        require = 0)
    private void cointcore$auditCanInteractWith(EntityPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) return;
        String uid = cointcore$getBackpackUid(this);
        BackpackSecurityAudit.logEvent(
            "CONTAINER_DENY_INTERACT",
            "player=" + (player == null ? "null" : player.getCommandSenderName()) + " uid=" + uid);
    }

    @Inject(
        method = "onContainerClosed(Lnet/minecraft/entity/player/EntityPlayer;)V",
        at = @At("HEAD"),
        remap = false,
        require = 0)
    private void cointcore$auditOnContainerClosed(EntityPlayer player, CallbackInfo ci) {
        String uid = cointcore$getBackpackUid(this);
        BackpackSecurityAudit.logEvent(
            "CONTAINER_CLOSED",
            "player=" + (player == null ? "null" : player.getCommandSenderName()) + " uid=" + uid);
        BackpackSecurityInspector.onContainerClosed(player == null ? "null" : player.getCommandSenderName(), uid);
    }

    @Inject(
        method = "canInteractWith(Lnet/minecraft/entity/player/EntityPlayer;)Z",
        at = @At("HEAD"),
        remap = false,
        require = 0)
    private void cointcore$auditCanInteractWithHead(EntityPlayer player, CallbackInfoReturnable<Boolean> cir) {
        String uid = cointcore$getBackpackUid(this);
        BackpackSecurityInspector.onContainerAccess(player == null ? "null" : player.getCommandSenderName(), uid);
    }
}
