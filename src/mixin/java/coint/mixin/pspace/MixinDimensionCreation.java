package coint.mixin.pspace;

import coint.player.TeamsManager;
import me.eigenraven.personalspace.block.PortalTileEntity;
import me.eigenraven.personalspace.world.DimensionConfig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;

@Mixin(value = PortalTileEntity.class, remap = false)
public class MixinDimensionCreation {

    @Shadow
    public int targetDimId;

    @Inject(method = "updateSettings", remap = false,
        at = @At(
            value = "INVOKE",
            target = "Lme/eigenraven/personalspace/world/DimensionConfig;getGroundLevel()I",
            shift = At.Shift.AFTER,
            by = 1
        )
    )
    private void cointcore$onDimCreated(EntityPlayerMP player, DimensionConfig unsafeConfig, CallbackInfo ci) {
        if (Universe.get().getPlayer(player).hasTeam()) {
            short uid = Universe.get().getPlayer(player).team.getUID();
            TeamsManager.get().setDimensionId(uid, targetDimId);
        }
    }

    @Inject(method = "updateSettings", remap = false, cancellable = true,
        at = @At(
            value = "INVOKE",
            target = "Lme/eigenraven/personalspace/world/DimensionConfig;nextFreeDimId()I"
        )
    )
    private void cointcore$beforeDimCreated(EntityPlayerMP player, DimensionConfig unsafeConfig, CallbackInfo ci) {
        ForgePlayer p = Universe.get().getPlayer(player);
        if (p.isOP()) {
            return;
        }

        short uid = p.team.getUID();
        if (TeamsManager.get().isDimBound(uid)) {
            ci.cancel();
            player.addChatMessage(new ChatComponentText("К вашей команде уже привязано персональное измерение. Обратитесь к администрации, если вы утратили доступ к старому порталу"));
        }
    }
}
