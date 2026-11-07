package coint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;

import coint.cluster.RemoteMe;
import coint.network.PacketRemoteMeAction;
import coint.network.WorldTravelNetwork;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * How many to craft, asked over AE2's terminal window onto a remote network. The terminal window stays open behind it
 * and comes back when this closes.
 */
@SideOnly(Side.CLIENT)
public class GuiRemoteCraftAmount extends GuiScreen {

    private static final int W = 180, H = 84;

    private final GuiScreen parent;
    private final int windowId;
    private final ItemStack kind;
    private GuiTextField field;

    private GuiRemoteCraftAmount(GuiScreen parent, int windowId, ItemStack kind) {
        this.parent = parent;
        this.windowId = windowId;
        this.kind = kind;
    }

    public static void ask(int windowId, ItemStack kind) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> {
            if (kind != null && mc.thePlayer != null
                && mc.thePlayer.openContainer != null
                && mc.thePlayer.openContainer.windowId == windowId
                && mc.currentScreen != null
                && !(mc.currentScreen instanceof GuiRemoteCraftAmount)) {
                mc.displayGuiScreen(new GuiRemoteCraftAmount(mc.currentScreen, windowId, kind));
            }
        });
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        int x = (width - W) / 2, y = (height - H) / 2;
        field = new GuiTextField(fontRendererObj, x + 10, y + 34, W - 20, 14);
        field.setMaxStringLength(7);
        field.setText("1");
        field.setFocused(true);
        buttonList.clear();
        buttonList.add(new GuiButton(0, x + 10, y + 56, 76, 20, "Заказать"));
        buttonList.add(new GuiButton(1, x + W - 86, y + 56, 76, 20, "Отмена"));
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void updateScreen() {
        field.updateCursorCounter();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int x = (width - W) / 2, y = (height - H) / 2;
        drawRect(x - 1, y - 1, x + W + 1, y + H + 1, 0xFF000000);
        drawRect(x, y, x + W, y + H, 0xFFC6C6C6);
        fontRendererObj.drawString("Заказать крафт в удалённой сети:", x + 10, y + 8, 0x404040);
        fontRendererObj
            .drawString(fontRendererObj.trimStringToWidth(kind.getDisplayName(), W - 20), x + 10, y + 20, 0x202080);
        field.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            back();
        } else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            order();
        } else if (Character.isDigit(c) || key == Keyboard.KEY_BACK
            || key == Keyboard.KEY_DELETE
            || key == Keyboard.KEY_LEFT
            || key == Keyboard.KEY_RIGHT) {
                field.textboxKeyTyped(c, key);
            }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        field.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            order();
        } else {
            back();
        }
    }

    private void order() {
        long amount;
        try {
            amount = Long.parseLong(
                field.getText()
                    .trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (amount <= 0) {
            return;
        }
        WorldTravelNetwork.CHANNEL.sendToServer(
            new PacketRemoteMeAction(
                windowId,
                RemoteMe.ACTION_CRAFT,
                kind,
                (int) Math.min(amount, RemoteMe.MAX_CRAFT)));
        back();
    }

    private void back() {
        mc.displayGuiScreen(parent);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
