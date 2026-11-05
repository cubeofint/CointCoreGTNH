package coint.mixin.personalspace;

import java.awt.Rectangle;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.client.GuiPDimDeleteConfirm;
import me.eigenraven.personalspace.block.PortalTileEntity;
import me.eigenraven.personalspace.gui.GuiEditWorld;
import me.eigenraven.personalspace.gui.WButton;
import me.eigenraven.personalspace.gui.Widget;
import me.eigenraven.personalspace.world.DimensionConfig;

/**
 * A delete button next to Done and Cancel in the window of a linked personal space portal (the free spot right of
 * Cancel). It asks once more, then the server checks that this is the player's own team's dimension.
 */
@Mixin(value = GuiEditWorld.class, remap = false)
public abstract class MixinGuiEditWorldDeleteButton {

    @Shadow
    public PortalTileEntity tile;

    @Shadow
    public Widget rootWidget;

    @Inject(method = "initGui", at = @At("TAIL"), remap = true)
    private void cointcore$addDeleteButton(CallbackInfo ci) {
        if (tile == null || !tile.active || tile.getWorldObj() == null) {
            return;
        }
        int here = tile.getWorldObj().provider.dimensionId;
        boolean personal = isPersonal(here) || isPersonal(tile.targetDimId);
        if (!personal) {
            return;
        }
        GuiEditWorld self = (GuiEditWorld) (Object) this;
        int x = tile.xCoord, y = tile.yCoord, z = tile.zCoord;
        WButton delete = new WButton(
            new Rectangle(260, 211, 44, 20),
            "Удалить",
            true,
            0xFF5555,
            null,
            () -> Minecraft.getMinecraft()
                .displayGuiScreen(new GuiPDimDeleteConfirm(self, x, y, z)));
        delete.tooltip = "Удалить персоналку команды навсегда";
        rootWidget.addChild(delete);
    }

    private static boolean isPersonal(int dimId) {
        return dimId > 0 && DimensionConfig.getForDimension(dimId, false) != null;
    }
}
