package coint.mixin.thaumcraft;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import serverutils.data.ClaimedChunks;
import thaumcraft.api.wands.ItemFocusBasic;
import thaumcraft.common.items.wands.ItemWandCasting;

/**
 * Закрывает обход защиты привата (ServerUtilities chunk claiming) через жезлы Thaumcraft.
 *
 * <p>
 * <b>Вектор 1 — набалдашник (IWandable):</b> правый клик жезлом на блок/TileEntity,
 * реализующий {@code IWandable}, вызывает {@code onWandRightClick} напрямую,
 * <em>до</em> фокусного диспатча и полностью в обход {@code PlayerInteractEvent}.
 * Защита: два {@code @Inject} с {@code LocalCapture.CAPTURE_FAILSOFT} перед каждым из двух
 * вызовов {@code IWandable.onWandRightClick} внутри {@code ItemWandCasting.onItemRightClick}.
 * Координаты блока извлекаются прямо из локальных переменных (local 5/6/7 = blockX/Y/Z),
 * которые уже вычислены к этому моменту.
 *
 * <p>
 * <b>Вектор 2 — фокус (Focus):</b> {@code ItemFocusPortableHole}, {@code ItemFocusTrade} и др.
 * переопределяют {@code onFocusRightClick} и вызывают {@code world.setBlock()} напрямую.
 * Защита: {@code @Redirect} на вызов {@code focus.onFocusRightClick} — единственная точка
 * диспатча для всех фокусов.
 */
@Mixin(value = ItemWandCasting.class, remap = false)
public class MixinItemFocusBasic {

    // ── IWandable path (набалдашник, до фокусного диспатча) ──────────────────

    /**
     * Перехватывает взаимодействие жезла с блоком (Block instanceof IWandable, ordinal 0).
     * {@code mop} включён в захват — нужен как маркер: если CAPTURE_FAILSOFT не смог
     * захватить locals, mop будет null и проверка пропускается (безопасный fallback).
     */
    @Inject(
        method = "onItemRightClick",
        at = @At(
            value = "INVOKE",
            target = "Lthaumcraft/api/wands/IWandable;onWandRightClick(Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/player/EntityPlayer;)Lnet/minecraft/item/ItemStack;",
            ordinal = 0),
        cancellable = true,
        locals = LocalCapture.CAPTURE_FAILSOFT,
        remap = false)
    private void cointcore$guardWandableBlock(ItemStack wandstack, World world, EntityPlayer player,
        CallbackInfoReturnable<ItemStack> cir, MovingObjectPosition mop, int blockX, int blockY, int blockZ) {
        if (mop != null) {
            cointcore$checkWandableClaimGuard(wandstack, world, player, cir, blockX, blockY, blockZ);
        }
    }

    /**
     * Перехватывает взаимодействие жезла с TileEntity (TileEntity instanceof IWandable, ordinal 1).
     */
    @Inject(
        method = "onItemRightClick",
        at = @At(
            value = "INVOKE",
            target = "Lthaumcraft/api/wands/IWandable;onWandRightClick(Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/player/EntityPlayer;)Lnet/minecraft/item/ItemStack;",
            ordinal = 1),
        cancellable = true,
        locals = LocalCapture.CAPTURE_FAILSOFT,
        remap = false)
    private void cointcore$guardWandableTileEntity(ItemStack wandstack, World world, EntityPlayer player,
        CallbackInfoReturnable<ItemStack> cir, MovingObjectPosition mop, int blockX, int blockY, int blockZ) {
        if (mop != null) {
            cointcore$checkWandableClaimGuard(wandstack, world, player, cir, blockX, blockY, blockZ);
        }
    }

    /**
     * Общая логика проверки для обоих IWandable-инджектов.
     *
     * <p>
     * При блокировке вызывает {@code cir.setReturnValue(wandstack)}, что неявно выставляет
     * {@code cancelled = true}: Mixin вставляет проверку сразу после инджекта и, если
     * метод отменён, выполняет {@code return cir.getReturnValue()} — вызов
     * {@code IWandable.onWandRightClick} пропускается, кулдаун не выставляется.
     */
    @Unique
    private static void cointcore$checkWandableClaimGuard(ItemStack wandstack, World world, EntityPlayer player,
        CallbackInfoReturnable<ItemStack> cir, int blockX, int blockY, int blockZ) {

        if (world.isRemote) return;

        if (ClaimedChunks.isActive() && ClaimedChunks.blockBlockEditing(player, blockX, blockY, blockZ, 1)) {
            player.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + "Вы не можете использовать жезл в чужом привате!"));
            cir.setReturnValue(wandstack);
        }
    }

    // ── Focus path ────────────────────────────────────────────────────────────

    /**
     * Перехватывает виртуальный вызов {@code focus.onFocusRightClick()} в методе
     * {@code ItemWandCasting.onItemRightClick}. Если целевой блок находится в чужом привате,
     * вызов отменяется (возвращается {@code null}, как у базового класса), виз не расходуется.
     */
    @Redirect(
        method = "onItemRightClick",
        at = @At(
            value = "INVOKE",
            target = "Lthaumcraft/api/wands/ItemFocusBasic;onFocusRightClick(Lnet/minecraft/item/ItemStack;Lnet/minecraft/world/World;Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/util/MovingObjectPosition;)Lnet/minecraft/item/ItemStack;"),
        remap = false)
    private ItemStack cointcore$guardFocusRightClick(ItemFocusBasic focus, ItemStack wandstack, World world,
        EntityPlayer player, MovingObjectPosition mop) {

        if (world.isRemote) {
            return focus.onFocusRightClick(wandstack, world, player, mop);
        }

        if (mop != null && mop.typeOfHit == MovingObjectType.BLOCK
            && ClaimedChunks.isActive()
            && ClaimedChunks.blockBlockEditing(player, mop.blockX, mop.blockY, mop.blockZ, mop.sideHit)) {
            player.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.RED + "Вы не можете использовать фокус жезла в чужом привате!"));
            return null;
        }

        return focus.onFocusRightClick(wandstack, world, player, mop);
    }

    /**
     * Перехватывает любой правый клик жезлом (включая фокусы и IWandable) и проверяет,
     * находится ли целевой блок в привате. Если да — блокирует вызов (возвращает wandstack,
     * как и в других инджектах), виз не расходуется.
     */
    @Inject(method = "onItemRightClick", at = @At("HEAD"), cancellable = true, remap = false)
    private void cointcore$guardAnyWandRightClick(ItemStack wandstack, World world, EntityPlayer player,
        CallbackInfoReturnable<ItemStack> cir) {
        if (world.isRemote || !ClaimedChunks.isActive()) {
            return;
        }

        MovingObjectPosition mop = player.rayTrace(5.0D, 1.0F);
        if (mop != null && mop.typeOfHit == MovingObjectType.BLOCK
            && ClaimedChunks.blockBlockEditing(player, mop.blockX, mop.blockY, mop.blockZ, mop.sideHit)) {
            player.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + "Вы не можете использовать жезл в чужом привате!"));
            cir.setReturnValue(wandstack);
        }
    }
}
