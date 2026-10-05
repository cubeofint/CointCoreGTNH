package coint.mixin.serverutilities;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.registry.GameRegistry.UniqueIdentifier;
import serverutils.ServerUtilitiesConfig;
import serverutils.data.ClaimedChunks;

@Mixin(value = ClaimedChunks.class, remap = false)
public abstract class MixinClaimedChunksDisabledRightClick {

    @Redirect(
        method = "onPlayerInteraction",
        at = @At(
            value = "INVOKE",
            target = "Lserverutils/ServerUtilitiesConfig$WorldConfig;isItemRightClickDisabled(Lnet/minecraft/item/ItemStack;)Z"))
    private static boolean cointcore$safeDisabledRightClick(ServerUtilitiesConfig.WorldConfig worldConfig,
        ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return false;
        }

        UniqueIdentifier id = GameRegistry.findUniqueIdentifierFor(stack.getItem());
        if (id != null && id.modId != null && worldConfig.disabled_right_click_items != null) {
            for (String raw : worldConfig.disabled_right_click_items) {
                if (raw == null) continue;

                String entry = raw.trim();
                if (entry.isEmpty()) continue;

                String[] parts = entry.split("@", 2);
                String key = parts[0].trim();
                int colon = key.indexOf(':');
                if (colon <= 0 || colon == key.length() - 1) continue;
                if (!"*".equals(
                    key.substring(colon + 1)
                        .trim()))
                    continue;
                if (!id.modId.equalsIgnoreCase(
                    key.substring(0, colon)
                        .trim()))
                    continue;

                if (parts.length == 1 || parts[1].trim()
                    .startsWith("*")) {
                    return true;
                }

                try {
                    if (stack.getItemDamage() == Integer.parseInt(parts[1].trim())) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        return worldConfig.isItemRightClickDisabled(stack);
    }
}
