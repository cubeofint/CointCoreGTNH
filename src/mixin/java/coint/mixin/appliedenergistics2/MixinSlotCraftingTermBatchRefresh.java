package coint.mixin.appliedenergistics2;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.container.slot.SlotCraftingTerm;
import appeng.helpers.InventoryAction;

@Mixin(value = SlotCraftingTerm.class, remap = false)
public abstract class MixinSlotCraftingTermBatchRefresh {

    @Unique
    private boolean cointcore$deferMatrixRefresh;

    @Unique
    private Container cointcore$deferredContainer;

    @Unique
    private IInventory cointcore$deferredInventory;

    @Inject(method = "doClick", at = @At("HEAD"), remap = false)
    private void cointcore$beginDeferredRefresh(InventoryAction action, EntityPlayer player, CallbackInfo ci) {
        cointcore$deferMatrixRefresh = player != null && player.worldObj != null
            && !player.worldObj.isRemote
            && (action == InventoryAction.CRAFT_SHIFT || action == InventoryAction.CRAFT_STACK);
        cointcore$deferredContainer = null;
        cointcore$deferredInventory = null;
    }

    @Inject(method = "doClick", at = @At("RETURN"), remap = false)
    private void cointcore$finishDeferredRefresh(InventoryAction action, EntityPlayer player, CallbackInfo ci) {
        Container container = cointcore$deferredContainer;
        IInventory inventory = cointcore$deferredInventory;
        cointcore$deferMatrixRefresh = false;
        cointcore$deferredContainer = null;
        cointcore$deferredInventory = null;
        if (container != null && inventory != null) {
            container.onCraftMatrixChanged(inventory);
        }
    }

    @Redirect(
        method = "craftItem",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/inventory/Container;onCraftMatrixChanged(Lnet/minecraft/inventory/IInventory;)V",
            ordinal = 1,
            remap = true),
        remap = false,
        require = 0)
    private void cointcore$deferRedundantMatrixRefresh(Container container, IInventory inventory) {
        if (cointcore$deferMatrixRefresh) {
            cointcore$deferredContainer = container;
            cointcore$deferredInventory = inventory;
            return;
        }
        container.onCraftMatrixChanged(inventory);
    }
}
