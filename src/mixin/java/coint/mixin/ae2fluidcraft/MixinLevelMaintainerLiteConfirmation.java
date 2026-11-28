package coint.mixin.ae2fluidcraft;

import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.glodblock.github.client.gui.container.ContainerLevelMaintainer;
import com.glodblock.github.common.tile.TileLevelMaintainer;
import com.glodblock.github.network.CPacketLevelMaintainer;

import appeng.me.GridAccessException;
import appeng.me.cache.CraftingGridCache;
import coint.integration.appliedenergistics2.LiteCraftingMode;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

@Mixin(value = CPacketLevelMaintainer.Handler.class, remap = false)
public abstract class MixinLevelMaintainerLiteConfirmation {

    @Unique
    private static final String cointcore$ON_MESSAGE = "onMessage(Lcom/glodblock/github/network/CPacketLevelMaintainer;"
        + "Lcpw/mods/fml/common/network/simpleimpl/MessageContext;)Lcpw/mods/fml/common/network/simpleimpl/IMessage;";

    @Inject(
        method = cointcore$ON_MESSAGE,
        at = @At(value = "INVOKE", target = "Lcom/glodblock/github/common/tile/TileLevelMaintainer;toggleLiteMode()V"),
        cancellable = true)
    private void cointcore$toggleMode(CPacketLevelMaintainer message, MessageContext ctx,
        CallbackInfoReturnable<IMessage> cir) {
        cointcore$change(ctx, cir, false);
    }

    @Inject(
        method = cointcore$ON_MESSAGE,
        at = @At(value = "INVOKE", target = "Lcom/glodblock/github/common/tile/TileLevelMaintainer;clearLiteMode()V"),
        cancellable = true)
    private void cointcore$inheritMode(CPacketLevelMaintainer message, MessageContext ctx,
        CallbackInfoReturnable<IMessage> cir) {
        cointcore$change(ctx, cir, true);
    }

    @Unique
    private static void cointcore$change(MessageContext ctx, CallbackInfoReturnable<IMessage> cir, boolean inherit) {
        if (!LiteCraftingMode.isEndgame()) return;
        EntityPlayerMP player = ctx.getServerHandler().playerEntity;
        if (!(player.openContainer instanceof ContainerLevelMaintainer container)) return;
        cir.setReturnValue(null);
        LiteCraftingMode.change(player, container, () -> {
            TileLevelMaintainer tile = container.getTile();
            boolean current = tile.isLiteMode();
            boolean next = inherit ? cointcore$networkDefault(tile) : !current;
            Runnable apply = () -> {
                if (inherit) tile.clearLiteMode();
                else tile.setLiteMode(next);
                container.updateGui();
            };
            if (current && !next) {
                LiteCraftingMode.confirm(
                    player,
                    container,
                    inherit ? LiteCraftingMode.Scope.INHERIT : LiteCraftingMode.Scope.MAINTAINER,
                    apply);
            } else {
                apply.run();
            }
        });
    }

    @Unique
    private static boolean cointcore$networkDefault(TileLevelMaintainer tile) {
        try {
            return tile.getProxy()
                .getCrafting() instanceof CraftingGridCache cache && cache.getLiteCraftingDefault();
        } catch (GridAccessException ignored) {
            return false;
        }
    }
}
