package coint.client.spotify;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class GuiSpotifyHudEditor extends GuiScreen {

    private final GuiScreen parent;
    private SpotifyHudLayout layout;

    public GuiSpotifyHudEditor(GuiScreen parent) {
        this.parent = parent;
        layout = ClientSpotify.layout.copy();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        buttonList.clear();
        int x = width / 2;
        buttonList.add(new GuiButton(0, x - 150, height - 28, 96, 20, I18n.format("cointcore.spotify.reset")));
        buttonList.add(new GuiButton(1, x - 48, height - 28, 96, 20, I18n.format("gui.done")));
        buttonList.add(new GuiButton(2, x + 54, height - 28, 96, 20, I18n.format("gui.cancel")));
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        boolean overButton = false;
        for (Object value : buttonList) {
            GuiButton control = (GuiButton) value;
            overButton |= x >= control.xPosition && x < control.xPosition + control.width
                && y >= control.yPosition
                && y < control.yPosition + control.height;
        }
        if (!overButton && button == 0 && layout.begin(x, y, width, height)) return;
        super.mouseClicked(x, y, button);
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long elapsed) {
        if (button == 0) layout.drag(x, y, width, height);
    }

    @Override
    protected void mouseMovedOrUp(int x, int y, int button) {
        if (button == 0) layout.end();
        super.mouseMovedOrUp(x, y, button);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();
        if (delta != 0) layout.zoom(
            Mouse.getEventX() * width / mc.displayWidth,
            height - Mouse.getEventY() * height / mc.displayHeight - 1,
            delta,
            width,
            height);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) layout = new SpotifyHudLayout(1, 0, 1);
        else {
            if (button.id == 1) ClientSpotify.saveLayout(layout);
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE) mc.displayGuiScreen(parent);
        else if (key == Keyboard.KEY_RETURN) actionPerformed(new GuiButton(1, 0, 0, ""));
        else super.keyTyped(character, key);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        fontRendererObj
            .drawSplitString(I18n.format("cointcore.spotify.editorHelp"), 10, 10, Math.min(300, width - 20), 0xD0E0D6);
        SpotifyHud.draw(
            fontRendererObj,
            layout.bounds(width, height),
            ClientSpotify.BRIDGE.snapshot(),
            true,
            System.currentTimeMillis());
        drawCenteredString(
            fontRendererObj,
            I18n.format("cointcore.spotify.widgetSize", Math.round(layout.bounds(width, height).scale * 100)),
            width / 2,
            height - 42,
            0xD0E0D6);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
