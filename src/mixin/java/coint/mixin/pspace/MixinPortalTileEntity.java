package coint.mixin.pspace;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.mixin.CointMixinPlugin;
import coint.player.TeamsManager;
import me.eigenraven.personalspace.world.DimensionConfig;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;

@Pseudo
@Mixin(targets = "me.eigenraven.personalspace.block.PortalTileEntity", remap = false)
public class MixinPortalTileEntity {

    @Shadow
    public int targetDimId;

    @Inject(
        method = "updateSettings",
        remap = false,
        at = @At(
            value = "INVOKE",
            target = "Lme/eigenraven/personalspace/world/DimensionConfig;getGroundLevel()I",
            shift = At.Shift.AFTER))
    private void cointcore$onDimCreated(EntityPlayerMP player, DimensionConfig unsafeConfig, CallbackInfo ci) {
        CointMixinPlugin.LOG.info("after create");
        if (Universe.get()
            .getPlayer(player)
            .hasTeam()) {
            short uid = Universe.get()
                .getPlayer(player).team.getUID();
            TeamsManager.get()
                .setDimensionId(uid, targetDimId);
        }
    }

    @Inject(
        method = "updateSettings",
        remap = false,
        cancellable = true,
        at = @At(value = "INVOKE", target = "Lme/eigenraven/personalspace/world/DimensionConfig;nextFreeDimId()I"))
    private void cointcore$beforeDimCreated(EntityPlayerMP player, DimensionConfig unsafeConfig, CallbackInfo ci) {
        CointMixinPlugin.LOG.info("before create");
        ForgePlayer p = Universe.get()
            .getPlayer(player);
        if (p.isOP()) {
            return;
        }

        if (!p.hasTeam()) {
            ci.cancel();
            player.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Нужно находится в команде"));
        }

        short uid = p.team.getUID();
        if (TeamsManager.get()
            .isDimBound(uid)) {
            ci.cancel();
            player.addChatMessage(
                new ChatComponentText(
                    "К вашей команде уже привязано персональное измерение. Обратитесь к администрации, если вы утратили доступ к старому порталу"));
        }
    }
}
