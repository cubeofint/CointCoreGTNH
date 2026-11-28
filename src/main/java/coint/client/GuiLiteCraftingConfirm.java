package coint.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;

import org.lwjgl.input.Keyboard;

import coint.integration.appliedenergistics2.LiteCraftingMode;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Drawn and given input in place so Minecraft keeps the original AE2 container open. */
public final class GuiLiteCraftingConfirm extends GuiScreen {

    private static final String LANG = "cointcore.ae2.liteCrafting.";
    private static final int PADDING = 12;
    private static GuiLiteCraftingConfirm active;

    private final GuiContainer parent;
    private final LiteCraftingMode.Prompt prompt;
    private final List<String> lines = new ArrayList<>();
    private int left, top, panelWidth, panelHeight, lineHeight;

    private GuiLiteCraftingConfirm(GuiContainer parent, LiteCraftingMode.Prompt prompt) {
        this.parent = parent;
        this.prompt = prompt;
    }

    public static void receive(NetworkManager connection, LiteCraftingMode.Prompt prompt) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> {
            NetHandlerPlayClient handler = mc.getNetHandler();
            if (handler == null || handler.getNetworkManager() != connection || !connection.isChannelOpen()) return;
            if (!(mc.currentScreen instanceof GuiContainer parent) || mc.thePlayer == null
                || parent.inventorySlots != mc.thePlayer.openContainer
                || parent.inventorySlots.windowId != prompt.windowId) return;
            active = new GuiLiteCraftingConfirm(parent, prompt);
            active.resizeToParent(mc);
        });
    }

    public static boolean handleInputFor(GuiScreen screen) {
        GuiLiteCraftingConfirm dialog = active;
        if (dialog == null || dialog.parent != screen) return false;
        dialog.resizeToParent(Minecraft.getMinecraft());
        dialog.handleInput();
        return true;
    }

    private void resizeToParent(Minecraft mc) {
        if (width != parent.width || height != parent.height) setWorldAndResolution(mc, parent.width, parent.height);
    }

    @Override
    public void initGui() {
        panelWidth = Math.min(350, width - 16);
        int lineWidth = panelWidth - 2 * PADDING;
        lineHeight = fontRendererObj.FONT_HEIGHT + 2;
        lines.clear();
        lines.addAll(fontRendererObj.listFormattedStringToWidth(tr("purpose"), lineWidth));
        lines.add("");
        lines.addAll(fontRendererObj.listFormattedStringToWidth(tr("when"), lineWidth));
        lines.add("");
        String scope = prompt.scope.name()
            .toLowerCase(Locale.ROOT);
        lines.addAll(fontRendererObj.listFormattedStringToWidth(tr("scope." + scope), lineWidth));

        panelHeight = 58 + lines.size() * lineHeight;
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int buttonWidth = (lineWidth - 6) / 2;
        int buttonY = top + panelHeight - PADDING - 20;
        buttonList.add(new GuiButton(0, left + PADDING, buttonY, buttonWidth, 20, tr("keep")));
        buttonList.add(
            new GuiButton(
                1,
                left + panelWidth - PADDING - buttonWidth,
                buttonY,
                buttonWidth,
                20,
                tr(prompt.scope == LiteCraftingMode.Scope.INHERIT ? "inherit" : "disable")));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, 0xA8000000);
        drawRect(left - 1, top - 1, left + panelWidth + 1, top + panelHeight + 1, 0xFF6C7078);
        drawRect(left, top, left + panelWidth, top + panelHeight, 0xFF202229);
        drawCenteredString(fontRendererObj, tr("title"), width / 2, top + PADDING, 0xFFFFFF);
        int y = top + 28;
        for (String line : lines) {
            fontRendererObj.drawString(line, left + PADDING, y, 0xDDDDDD);
            y += lineHeight;
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void keyTyped(char character, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) finish(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        finish(button.id == 1);
    }

    private void finish(boolean accepted) {
        if (active != this) return;
        active = null;
        LiteCraftingMode.reply(prompt.id, accepted);
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(LANG + key);
    }

    public static final class Listener {

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void draw(GuiScreenEvent.DrawScreenEvent.Post event) {
            GuiLiteCraftingConfirm dialog = active;
            if (dialog == null || dialog.parent != event.gui) return;
            dialog.resizeToParent(Minecraft.getMinecraft());
            dialog.drawScreen(event.mouseX, event.mouseY, event.renderPartialTicks);
        }

        @SubscribeEvent
        public void open(GuiOpenEvent event) {
            if (active != null && event.gui != active.parent) active = null;
        }
    }
}
