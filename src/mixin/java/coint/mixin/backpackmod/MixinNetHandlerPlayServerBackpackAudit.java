package coint.mixin.backpackmod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.C0EPacketClickWindow;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.debug.BackpackSecurityAudit;
import coint.debug.BackpackSecurityInspector;

@Mixin(NetHandlerPlayServer.class)
public abstract class MixinNetHandlerPlayServerBackpackAudit {

    @Shadow
    public EntityPlayerMP playerEntity;

    @Unique
    private static boolean cointcore$isContainerAdvanced(Container container) {
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

    @Inject(method = "processClickWindow", at = @At("HEAD"))
    private void cointcore$auditProcessClickWindow(C0EPacketClickWindow packet, CallbackInfo ci) {
        Container open = playerEntity.openContainer;
        if (!cointcore$isContainerAdvanced(open)) return;
        String uid = cointcore$getBackpackUid(open);
        BackpackSecurityAudit.logEvent(
            "PACKET_CLICK_WINDOW",
            "player=" + playerEntity.getCommandSenderName()
                + " uid="
                + uid
                + " windowId="
                + packet.func_149548_c()
                + " slotId="
                + packet.func_149544_d()
                + " button="
                + packet.func_149543_e()
                + " mode="
                + packet.func_149542_h());
        BackpackSecurityInspector.onClick(
            playerEntity.getCommandSenderName(),
            uid,
            packet.func_149544_d(),
            packet.func_149543_e(),
            packet.func_149542_h(),
            "packet");
    }
}
