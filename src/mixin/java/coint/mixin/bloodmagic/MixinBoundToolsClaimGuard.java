package coint.mixin.bloodmagic;

import java.util.Objects;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import serverutils.data.ClaimedChunks;

/**
 * Secures Blood Magic Bound tools AoE break in ServerUtilities claims.
 *
 * <p>
 * Upstream code calls {@code World#setBlockToAir} directly during right-click AoE
 * mining. We keep vanilla Blood Magic behavior, but deny deletion in foreign
 * claimed chunks via explicit ServerUtilities checks.
 */
@Mixin(
    targets = { "WayofTime.alchemicalWizardry.common.items.BoundPickaxe",
        "WayofTime.alchemicalWizardry.common.items.BoundShovel", "WayofTime.alchemicalWizardry.common.items.BoundAxe" },
    remap = false)
public abstract class MixinBoundToolsClaimGuard {

    @Unique
    private static final Logger COINT_LOGGER = LogManager.getLogger("cointcore-mixin");
    @Unique
    private static boolean cointcore$loggedFirstIntercept = false;

    @Redirect(
        method = "onItemRightClick(Lnet/minecraft/item/ItemStack;Lnet/minecraft/world/World;Lnet/minecraft/entity/player/EntityPlayer;)Lnet/minecraft/item/ItemStack;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;setBlockToAir(III)Z"),
        remap = false,
        require = 1)
    @SuppressWarnings("null")
    private boolean cointcore$guardAoEBreak(World world, int x, int y, int z, ItemStack stack, World methodWorld,
        EntityPlayer player) {
        if (world == null || player == null) {
            return false;
        }
        if (!cointcore$loggedFirstIntercept) {
            cointcore$loggedFirstIntercept = true;
            COINT_LOGGER.info(
                "[MixinDebug] bloodmagic redirect active: worldRemote={}, player={}, pos=({}, {}, {})",
                world.isRemote,
                player.getCommandSenderName(),
                x,
                y,
                z);
        }
        EntityPlayer safePlayer = Objects.requireNonNull(player, "player");
        if (!world.isRemote && ClaimedChunks.isActive() && cointcore$isClaimDenied(safePlayer, x, y, z)) {
            COINT_LOGGER.info(
                "[MixinDebug] bloodmagic denied by claim check: player={}, pos=({}, {}, {})",
                safePlayer.getCommandSenderName(),
                x,
                y,
                z);
            return false;
        }
        if (world.isRemote) {
            return world.setBlockToAir(x, y, z);
        }
        return world.setBlockToAir(x, y, z);
    }

    @Unique
    private static boolean cointcore$isClaimDenied(EntityPlayer player, int x, int y, int z) {
        for (int side = 0; side < 6; side++) {
            if (ClaimedChunks.blockBlockEditing(player, x, y, z, side)) {
                return true;
            }
        }
        return false;
    }
}
