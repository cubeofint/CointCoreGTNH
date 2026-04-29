package coint.mixin.minecraft;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.C0EPacketClickWindow;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Финальный серверный барьер от дюпа Forestry-рюкзаков через хоткей выброса из GUI-слота.
 *
 * <p>
 * Некоторые хоткеи (в т.ч. Ctrl+Alt+Q) могут обходить {@code EntityPlayer.dropOneItem(...)} и ходить
 * напрямую через пакет {@link C0EPacketClickWindow} (mode=4 "throw") по слоту хотбара.
 * Если слот рюкзака во время открытия GUI заменён на Forestry {@code SlotLocked}, то дроп не должен
 * приводить к спавну EntityItem.
 */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinNetHandlerPlayServerForestryBackpackClickBlock {

    @Shadow
    public EntityPlayerMP playerEntity;

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
    private static boolean cointcore$isForestrySlotLocked(Slot slot) {
        if (slot == null) return false;
        Class<?> cls = slot.getClass();
        while (cls != null) {
            if ("forestry.core.gui.slots.SlotLocked".equals(cls.getName())) {
                return true;
            }
            cls = cls.getSuperclass();
        }
        return false;
    }

    @Inject(method = "processClickWindow", at = @At("HEAD"), cancellable = true)
    private void blockForestryBackpackThrowFromLockedSlot(C0EPacketClickWindow packet, CallbackInfo ci) {
        int mode = packet.func_149542_h();
        Container open = playerEntity.openContainer;
        int slotId = packet.func_149544_d();

        if (!cointcore$isForestryContainerItemInventory(open)) {
            return;
        }

        // Главная защита: полностью запрещаем mode=4 ("throw") внутри Forestry ContainerItemInventory.
        // Это убирает зависимость от корректной установки SlotLocked и перекрывает хоткеи вида Ctrl+Alt+Q.
        if (mode == 4) {
            ci.cancel();
            return;
        }

        if (slotId < 0 || slotId >= open.inventorySlots.size()) {
            return;
        }

        Slot slot = open.getSlot(slotId);
        // В идеале SlotLocked вообще не должен принимать клики любого типа.
        // Если хоткей/мод шлёт нестандартные (mode=0) пакеты, отменяем их тут же.
        if (cointcore$isForestrySlotLocked(slot)) {
            ci.cancel();
        }
    }
}
