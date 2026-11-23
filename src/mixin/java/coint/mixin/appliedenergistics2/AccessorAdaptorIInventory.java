package coint.mixin.appliedenergistics2;

import net.minecraft.inventory.IInventory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.util.inv.AdaptorIInventory;

@Mixin(value = AdaptorIInventory.class, remap = false)
public interface AccessorAdaptorIInventory {

    @Accessor("i")
    IInventory cointcore$getInventory();
}
