package coint.supply;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;

import codechicken.nei.api.INEIGuiAdapter;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Lets an item be dragged from NEI onto a filter of the supply buffer window. */
@SideOnly(Side.CLIENT)
public class SupplyNeiHandler extends INEIGuiAdapter {

    @Override
    public boolean handleDragNDrop(GuiContainer gui, int mouseX, int mouseY, ItemStack dragged, int button) {
        return gui instanceof GuiSupplyBuffer window && window.dropFilter(mouseX, mouseY, dragged);
    }

    public static void register() {
        codechicken.nei.api.API.registerNEIGuiHandler(new SupplyNeiHandler());
    }
}
