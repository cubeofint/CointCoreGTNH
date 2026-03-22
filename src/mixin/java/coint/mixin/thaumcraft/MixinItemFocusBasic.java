package coint.mixin.thaumcraft;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import serverutils.data.ClaimedChunks;
import thaumcraft.api.wands.ItemFocusBasic;
import thaumcraft.common.items.wands.ItemWandCasting;

/**
 * Закрывает обход защиты привата (ServerUtilities chunk claiming) через фокусы жезлов Thaumcraft.
 *
 * <p>
 * Проблема: фокусы {@code ItemFocusPortableHole} («Переносная дыра») и {@code ItemFocusTrade}
 * («Обмен блоков») переопределяют {@code onFocusRightClick} и вызывают {@code world.setBlock()}
 * или {@code ServerTickEventsFML.addSwapper()} <b>напрямую</b>, минуя {@code PlayerInteractEvent}
 * и {@code BlockEvent.BreakEvent}, на которые опирается ServerUtilities.
 *
 * <p>
 * Решение: {@code @Redirect} на вызов {@code focus.onFocusRightClick(...)} внутри
 * {@code ItemWandCasting.onItemRightClick}. Это единственная точка диспатча для ВСЕХ фокусов —
 * перехват здесь покрывает любые текущие и будущие фокусы, независимо от того,
 * переопределяют ли они метод базового класса.
 *
 * <p>
 * Исключение: {@code ItemFocusExcavation} уже вызывает {@code ForgeHooks.onBlockBreakEvent()},
 * поэтому он защищён ServerUtilities через событие, но наш перехват его не ломает —
 * защита просто сработает дважды.
 */
@Mixin(value = ItemWandCasting.class, remap = false)
public class MixinItemFocusBasic {

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

        // Клиентская сторона не принимает решений о защите.
        if (world.isRemote) {
            return focus.onFocusRightClick(wandstack, world, player, mop);
        }

        // Если MOP указывает на блок — проверяем права доступа.
        if (mop != null && mop.typeOfHit == MovingObjectType.BLOCK
            && ClaimedChunks.isActive()
            && ClaimedChunks.blockBlockEditing(player, mop.blockX, mop.blockY, mop.blockZ, 0)) {
            player.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.RED + "Вы не можете использовать фокус жезла в чужом привате!"));
            // null = поведение базового класса, жезл возвращается без изменений, виз не тратится.
            return null;
        }

        return focus.onFocusRightClick(wandstack, world, player, mop);
    }
}
