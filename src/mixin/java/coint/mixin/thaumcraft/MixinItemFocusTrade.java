package coint.mixin.thaumcraft;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import serverutils.data.ClaimedChunks;
import thaumcraft.common.items.wands.foci.ItemFocusTrade;

/**
 * Закрывает второй вектор атаки фокуса «Обмен блоков» ({@code ItemFocusTrade}):
 * замену блока через <b>левый клик</b> ({@code onEntitySwing}).
 *
 * <p>
 * Первичный вектор (правый клик, {@code onFocusRightClick}) уже закрыт
 * {@link MixinItemFocusBasic} через {@code @Redirect} в {@code ItemWandCasting.onItemRightClick}.
 *
 * <p>
 * Дополнительный вектор: игрок подбирает образец блока из своего привата
 * (сник + правый клик — это разрешено), а затем <i>левым кликом</i> заменяет
 * блок в чужом привате. {@code onEntitySwing} вызывается напрямую из
 * {@code ItemWandCasting.onEntitySwing} без дополнительных проверок прав.
 */
@Mixin(value = ItemFocusTrade.class, remap = false)
public abstract class MixinItemFocusTrade {

    /**
     * Доступ к защищённому методу расчёта MOP, который {@link ItemFocusTrade} определяет
     * самостоятельно (не наследует). Теневой stub заменяется реальной реализацией Mixin.
     */
    @Shadow(remap = false)
    protected abstract MovingObjectPosition getMovingObjectPositionFromPlayer(World world, EntityPlayer player);

    /**
     * Проверяет права на редактирование блока до того, как {@code ItemFocusTrade.onEntitySwing}
     * передаёт координаты в {@code ServerTickEventsFML.addSwapper}. Если блок находится
     * в чужом привате — действие отменяется и игрок получает сообщение.
     */
    @Inject(method = "onEntitySwing", at = @At("HEAD"), cancellable = true)
    private void cointcore$guardTradeSwing(EntityLivingBase entityLiving, ItemStack stack,
        CallbackInfoReturnable<Boolean> cir) {

        if (entityLiving.worldObj.isRemote) {
            return;
        }

        if (!(entityLiving instanceof EntityPlayer player)) {
            return;
        }

        if (!ClaimedChunks.isActive()) {
            return;
        }

        MovingObjectPosition mop = this.getMovingObjectPositionFromPlayer(player.worldObj, player);
        if (mop != null && mop.typeOfHit == MovingObjectType.BLOCK
            && ClaimedChunks.blockBlockEditing(player, mop.blockX, mop.blockY, mop.blockZ, 0)) {

            player.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.RED + "Вы не можете использовать фокус жезла в чужом привате!"));
            cir.setReturnValue(true);
        }
    }
}
