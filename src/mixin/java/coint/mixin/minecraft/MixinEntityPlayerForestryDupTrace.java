package coint.mixin.minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityPlayer.class)
public abstract class MixinEntityPlayerForestryDupTrace {

    @Unique
    private static boolean cointcore$isForestryContainerItemInventory(Container container) {
        if (container == null) return false;
        Class<?> cls = container.getClass();
        while (cls != null) {
            if ("forestry.core.gui.ContainerItemInventory".equals(cls.getName())) {
                return true;
            }
            cls = cls.getSuperclass();
        }
        return false;
    }

    @Unique
    private static Object cointcore$getForestryItemInventory(Container container) {
        if (container == null) return null;
        Class<?> cls = container.getClass();
        while (cls != null) {
            try {
                Field inventoryField = cls.getDeclaredField("inventory");
                inventoryField.setAccessible(true);
                return inventoryField.get(container);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    @Unique
    private static boolean cointcore$isParentBackpackStack(Container openContainer, ItemStack stack) {
        if (stack == null) return false;
        Object inventory = cointcore$getForestryItemInventory(openContainer);
        if (inventory == null) return false;
        try {
            Method method = inventory.getClass()
                .getMethod("isParentItemInventory", ItemStack.class);
            Object result = method.invoke(inventory, stack);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Inject(method = "dropOneItem(Z)Lnet/minecraft/entity/item/EntityItem;", at = @At("HEAD"))
    private void cointcore$traceDropOneItemHead(boolean dropAll, CallbackInfoReturnable<EntityItem> cir) {
        // no-op: kept for potential future guards on dropOneItem path.
    }

    @Inject(method = "dropOneItem(Z)Lnet/minecraft/entity/item/EntityItem;", at = @At("RETURN"))
    private void cointcore$traceDropOneItemReturn(boolean dropAll, CallbackInfoReturnable<EntityItem> cir) {
        // no-op: kept for potential future guards on dropOneItem path.
    }

    @Inject(
        method = "dropPlayerItemWithRandomChoice(Lnet/minecraft/item/ItemStack;Z)Lnet/minecraft/entity/item/EntityItem;",
        at = @At("HEAD"),
        cancellable = true)
    private void cointcore$traceDropPlayerItemWithRandomChoiceHead(ItemStack stack, boolean dropAll,
        CallbackInfoReturnable<EntityItem> cir) {
        EntityPlayer self = (EntityPlayer) (Object) this;
        if (!cointcore$isForestryContainerItemInventory(self.openContainer)) return;

        // Hard-stop for shortcuts (e.g. bogosorter) that call drop directly and bypass window-click path.
        if (cointcore$isParentBackpackStack(self.openContainer, stack)) {
            cir.setReturnValue(null);
        }
    }

    @Inject(
        method = "dropPlayerItemWithRandomChoice(Lnet/minecraft/item/ItemStack;Z)Lnet/minecraft/entity/item/EntityItem;",
        at = @At("RETURN"))
    private void cointcore$traceDropPlayerItemWithRandomChoiceReturn(ItemStack stack, boolean dropAll,
        CallbackInfoReturnable<EntityItem> cir) {
        // no-op: cancellation guard runs at HEAD.
    }
}
