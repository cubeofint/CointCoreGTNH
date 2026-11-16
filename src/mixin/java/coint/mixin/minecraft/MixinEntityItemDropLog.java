package coint.mixin.minecraft;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import coint.integration.discord.DiscordDropLog;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;

@Mixin(EntityItem.class)
public class MixinEntityItemDropLog {

    @WrapOperation(
        method = "onCollideWithPlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/player/InventoryPlayer;addItemStackToInventory(Lnet/minecraft/item/ItemStack;)Z"))
    private boolean cointcore$actualPickup(InventoryPlayer inventory, ItemStack stack, Operation<Boolean> original) {
        DiscordDropLog.Pickup before = DiscordDropLog.beforePickup((EntityItem) (Object) this, inventory.player);
        boolean result = original.call(inventory, stack);
        DiscordDropLog.picked(before, stack.stackSize);
        return result;
    }

    @WrapOperation(
        method = "onCollideWithPlayer",
        at = @At(
            value = "INVOKE",
            target = "Lcpw/mods/fml/common/eventhandler/EventBus;post(Lcpw/mods/fml/common/eventhandler/Event;)Z",
            remap = false))
    private boolean cointcore$handledPickup(EventBus bus, Event event, Operation<Boolean> original) {
        EntityItemPickupEvent pickup = (EntityItemPickupEvent) event;
        EntityItem self = (EntityItem) (Object) this;
        DiscordDropLog.Pickup before = DiscordDropLog.beforePickup(self, pickup.entityPlayer);
        boolean canceled = original.call(bus, event);
        if (!canceled && event.getResult() == Event.Result.ALLOW)
            DiscordDropLog.picked(before, self.isDead ? 0 : self.getEntityItem().stackSize);
        return canceled;
    }

    @WrapMethod(method = "combineItems")
    private boolean cointcore$keepDropSources(EntityItem other, Operation<Boolean> original) {
        EntityItem self = (EntityItem) (Object) this;
        Object[] before = DiscordDropLog.beforeMerge(self, other);
        boolean result = original.call(other);
        if (result) DiscordDropLog.merged(self, other, before);
        return result;
    }
}
