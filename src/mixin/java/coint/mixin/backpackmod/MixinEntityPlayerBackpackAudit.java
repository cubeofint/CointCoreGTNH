package coint.mixin.backpackmod;

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

import coint.debug.BackpackSecurityAudit;
import coint.debug.BackpackSecurityInspector;

@Mixin(EntityPlayer.class)
public abstract class MixinEntityPlayerBackpackAudit {

    /** {@code true}, если открыт GUI рюкзака Adventure Backpack (ContainerAdvanced). */
    @Unique
    private static boolean cointcore$isBackpackAdvancedGuiOpen(Container container) {
        if (container == null) return false;
        Class<?> cls = container.getClass();
        while (cls != null) {
            if ("de.eydamos.backpack.inventory.container.ContainerAdvanced".equals(cls.getName())) {
                return true;
            }
            cls = cls.getSuperclass();
        }
        return false;
    }

    @Unique
    private static String cointcore$getBackpackUid(Container container) {
        if (container == null) return "null";
        try {
            Field f = container.getClass()
                .getDeclaredField("backpackSave");
            f.setAccessible(true);
            Object save = f.get(container);
            if (save == null) return "null";
            Method m = save.getClass()
                .getMethod("getUUID");
            Object value = m.invoke(save);
            return value == null ? "null" : value.toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    @Inject(method = "dropOneItem(Z)Lnet/minecraft/entity/item/EntityItem;", at = @At("HEAD"))
    private void cointcore$auditDropOneItem(boolean dropAll, CallbackInfoReturnable<EntityItem> cir) {
        EntityPlayer self = (EntityPlayer) (Object) this;
        if (cointcore$isBackpackAdvancedGuiOpen(self.openContainer)) {
            String uid = cointcore$getBackpackUid(self.openContainer);
            BackpackSecurityAudit.logEvent(
                "DIRECT_DROP_ONE_ITEM",
                "player=" + self.getCommandSenderName() + " uid=" + uid + " dropAll=" + dropAll);
            BackpackSecurityInspector.onDirectDrop(self.getCommandSenderName(), uid, "dropOneItem", "none");
        }
    }

    @Inject(
        method = "dropPlayerItemWithRandomChoice(Lnet/minecraft/item/ItemStack;Z)Lnet/minecraft/entity/item/EntityItem;",
        at = @At("HEAD"))
    private void cointcore$auditDropRandom(ItemStack stack, boolean dropAll, CallbackInfoReturnable<EntityItem> cir) {
        EntityPlayer self = (EntityPlayer) (Object) this;
        if (cointcore$isBackpackAdvancedGuiOpen(self.openContainer)) {
            String uid = cointcore$getBackpackUid(self.openContainer);
            BackpackSecurityAudit.logEvent(
                "DIRECT_DROP_RANDOM",
                "player=" + self.getCommandSenderName()
                    + " uid="
                    + uid
                    + " dropAll="
                    + dropAll
                    + " stack="
                    + (stack == null ? "null" : stack.toString()));
            BackpackSecurityInspector.onDirectDrop(
                self.getCommandSenderName(),
                uid,
                "dropPlayerItemWithRandomChoice",
                stack == null ? "null" : stack.toString());
        }
    }
}
