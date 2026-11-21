package coint.client.spotify;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

public final class GuiSpotify extends GuiScreen {

    private final GuiScreen parent;
    private int requestedVolume = -1;
    private long volumeRequestedAt;

    public GuiSpotify(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        buttonList.clear();
        int x = width / 2, y = height / 2;
        buttonList.add(new GuiButton(0, x - 140, y - 90, 280, 20, ""));
        buttonList.add(new GuiButton(1, x - 140, y + 8, 88, 20, I18n.format("cointcore.spotify.previous")));
        buttonList.add(new GuiButton(2, x - 44, y + 8, 88, 20, ""));
        buttonList.add(new GuiButton(3, x + 52, y + 8, 88, 20, I18n.format("cointcore.spotify.next")));
        buttonList.add(new GuiButton(4, x - 140, y + 36, 40, 20, "-"));
        buttonList.add(new GuiButton(5, x + 100, y + 36, 40, 20, "+"));
        buttonList.add(new GuiButton(6, x - 140, y + 64, 136, 20, ""));
        buttonList.add(new GuiButton(8, x + 4, y + 64, 136, 20, I18n.format("cointcore.spotify.editHud")));
        buttonList.add(new GuiButton(7, x - 140, y + 92, 280, 20, I18n.format("gui.done")));
        updateScreen();
    }

    @Override
    public void updateScreen() {
        SpotifyBridge.Snapshot song = ClientSpotify.BRIDGE.snapshot();
        for (Object value : buttonList) {
            GuiButton b = (GuiButton) value;
            if (b.id == 0) {
                b.displayString = I18n.format(
                    "cointcore.spotify.enabled",
                    I18n.format(ClientSpotify.enabled ? "options.on" : "options.off"));
                b.enabled = ClientSpotify.enabled || SpotifyBridge.supported();
            } else if (b.id >= 1 && b.id <= 5) {
                b.enabled = ClientSpotify.enabled && "ready".equals(song.status) && (b.id <= 3 || song.volume >= 0);
                if (b.id == 2)
                    b.displayString = I18n.format(song.playing ? "cointcore.spotify.pause" : "cointcore.spotify.play");
            } else if (b.id == 6) {
                b.displayString = I18n
                    .format("cointcore.spotify.hud", I18n.format(ClientSpotify.hud ? "options.on" : "options.off"));
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0 -> ClientSpotify.setEnabled(!ClientSpotify.enabled);
            case 1 -> ClientSpotify.BRIDGE.command("previous");
            case 2 -> ClientSpotify.BRIDGE.command("toggle");
            case 3 -> ClientSpotify.BRIDGE.command("next");
            case 4, 5 -> {
                requestedVolume = Math.max(0, Math.min(100, volume() + (button.id == 4 ? -5 : 5)));
                volumeRequestedAt = System.nanoTime();
                ClientSpotify.BRIDGE.command("volume " + requestedVolume);
            }
            case 6 -> ClientSpotify.toggleHud();
            case 7 -> mc.displayGuiScreen(parent);
            case 8 -> mc.displayGuiScreen(new GuiSpotifyHudEditor(this));
            default -> {}
        }
        updateScreen();
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        SpotifyBridge.Snapshot song = ClientSpotify.BRIDGE.snapshot();
        int left = width / 2 - 140;
        int top = height / 2 - 8;
        if (button == 0 && ClientSpotify.enabled
            && "ready".equals(song.status)
            && song.duration > 0
            && x >= left
            && x <= left + 280
            && y >= top - 3
            && y <= top + 7) ClientSpotify.BRIDGE.command("seek " + (song.duration * (x - left) / 280));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int x = width / 2, y = height / 2;
        SpotifyBridge.Snapshot song = ClientSpotify.BRIDGE.snapshot();
        drawCenteredString(fontRendererObj, "Spotify", x, y - 112, 0x1ED760);
        String status = !SpotifyBridge.supported() ? "unsupported" : !ClientSpotify.enabled ? "disabled" : song.status;
        if ("ready".equals(status)) {
            drawCenteredString(
                fontRendererObj,
                fontRendererObj.trimStringToWidth(song.title, 280),
                x,
                y - 57,
                0xFFFFFF);
            drawCenteredString(
                fontRendererObj,
                fontRendererObj.trimStringToWidth(song.artist, 280),
                x,
                y - 42,
                0xBBC6BE);
            drawCenteredString(fontRendererObj, time(song.position) + " / " + time(song.duration), x, y - 25, 0xBBC6BE);
        } else {
            fontRendererObj
                .drawSplitString(I18n.format("cointcore.spotify.status." + status), x - 140, y - 57, 280, 0xBBC6BE);
        }
        drawRect(x - 140, y - 8, x + 140, y - 5, 0xFF34453B);
        if (song.duration > 0 && "ready".equals(status))
            drawRect(x - 140, y - 8, x - 140 + (int) (280 * song.position / song.duration), y - 5, 0xFF1ED760);
        drawCenteredString(
            fontRendererObj,
            I18n.format("cointcore.spotify.volume", volume() < 0 ? "—" : volume() + "%"),
            x,
            y + 42,
            0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    static String time(long millis) {
        long seconds = millis / 1000;
        return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60);
    }

    private int volume() {
        return requestedVolume >= 0 && System.nanoTime() - volumeRequestedAt < 1_000_000_000L ? requestedVolume
            : ClientSpotify.BRIDGE.snapshot().volume;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
