package coint.mixin.minecraft;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.ForgeHooks;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import coint.integration.discord.DiscordDropLog;

@Mixin(value = ForgeHooks.class, remap = false)
public class MixinForgeHooksDropLog {

    @WrapMethod(method = "onPlayerTossEvent")
    private static EntityItem cointcore$logSpawnedToss(EntityPlayer player, ItemStack stack, boolean includeName,
        Operation<EntityItem> original) {
        EntityItem item = original.call(player, stack, includeName);
        DiscordDropLog.thrown(player, item);
        return item;
    }
}
