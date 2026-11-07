package coint.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.StatCollector;

import coint.network.PacketPDimDelete;
import coint.network.WorldTravelNetwork;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Asks once more before the delete button of a personal space portal goes to the server. */
@SideOnly(Side.CLIENT)
public class GuiPDimDeleteConfirm extends GuiScreen {

    private final GuiScreen parent;
    private final int x, y, z;

    public GuiPDimDeleteConfirm(GuiScreen parent, int x, int y, int z) {
        this.parent = parent;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        buttonList.clear();
        buttonList.add(
            new GuiButton(
                0,
                width / 2 - 155,
                height / 2 + 30,
                150,
                20,
                StatCollector.translateToLocal("cointcore.pdim.delete.confirm.yes")));
        buttonList.add(
            new GuiButton(1, width / 2 + 5, height / 2 + 30, 150, 20, StatCollector.translateToLocal("gui.cancel")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            WorldTravelNetwork.CHANNEL.sendToServer(new PacketPDimDelete(x, y, z));
            mc.displayGuiScreen(null);
        } else if (button.id == 1) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("cointcore.pdim.delete.confirm.title"),
            width / 2,
            height / 2 - 40,
            0xFFFFFF);
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("cointcore.pdim.delete.confirm.line1"),
            width / 2,
            height / 2 - 22,
            0xFFFFFF);
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("cointcore.pdim.delete.confirm.line2"),
            width / 2,
            height / 2 - 10,
            0xAAAAAA);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
