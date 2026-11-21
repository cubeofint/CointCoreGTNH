package coint.client.spotify;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;

import org.lwjgl.opengl.GL11;

/** Shared mini-player renderer for the overlay, chat controls and layout editor. */
final class SpotifyHud {

    private static String lastTrack = "";
    private static long trackSince;

    private SpotifyHud() {}

    static void draw(FontRenderer font, SpotifyHudLayout.Bounds box, SpotifyBridge.Snapshot song, boolean editing,
        long now) {
        draw(font, box, song, editing, now, SpotifyHudControls.NONE);
    }

    static void draw(FontRenderer font, SpotifyHudLayout.Bounds box, SpotifyBridge.Snapshot song, boolean editing,
        long now, int hover) {
        boolean ready = "ready".equals(song.status);
        String title = ready && !song.title.isEmpty() ? song.title : I18n.format("cointcore.spotify.sampleTrack");
        String artist = ready && !song.artist.isEmpty() ? song.artist : I18n.format("cointcore.spotify.sampleArtist");
        String track = title + "\n" + artist;
        if (!lastTrack.equals(track)) {
            lastTrack = track;
            trackSince = now;
        }
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(box.x, box.y, 0);
            GL11.glScalef(box.scale, box.scale, 1);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_BLEND);
            Gui.drawRect(0, 0, SpotifyHudLayout.WIDTH, SpotifyHudLayout.HEIGHT, 0xED101613);
            Gui.drawRect(0, 0, 3, SpotifyHudLayout.HEIGHT, 0xFF1ED760);
            Gui.drawRect(3, 0, SpotifyHudLayout.WIDTH, 18, 0xE3202C24);
            font.drawStringWithShadow("Spotify", 10, 5, 0x1ED760);
            String state = I18n.format(
                !ready ? "cointcore.spotify.preview"
                    : song.playing ? "cointcore.spotify.playing" : "cointcore.spotify.paused");
            font.drawStringWithShadow(state, SpotifyHudLayout.WIDTH - 10 - font.getStringWidth(state), 5, 0xACBDB2);
            if (!ready || !SpotifyCoverTexture.draw(song.coverId, ClientSpotify.BRIDGE.cover(), 10, 26, 64)) {
                Gui.drawRect(10, 26, 74, 90, 0xFF243A2D);
                Gui.drawRect(38, 42, 42, 72, 0xFF82B597);
                Gui.drawRect(42, 42, 54, 48, 0xFF82B597);
                Gui.drawRect(29, 67, 42, 76, 0xFF82B597);
            }
            line(font, "cointcore.spotify.track", title, 27, 0xFFFFFF, now - trackSince);
            line(font, "cointcore.spotify.artist", artist, 43, 0xBBC8C0, now - trackSince);
            long duration = ready ? song.duration : 180000;
            long position = ready ? ClientSpotify.INPUT.position(song) : 65000;
            font.drawStringWithShadow(GuiSpotify.time(position) + " / " + GuiSpotify.time(duration), 84, 63, 0x8FA99A);
            bar(
                SpotifyHudControls.SEEK_LEFT,
                SpotifyHudControls.SEEK_RIGHT,
                SpotifyHudControls.SEEK_Y,
                duration > 0 ? position / (double) duration : 0,
                hover == SpotifyHudControls.SEEK,
                ready && song.canSeek);
            button(font, SpotifyHudControls.SHUFFLE, 10, 22, hover, ready && song.canShuffle, song.shuffle, "><");
            button(
                font,
                SpotifyHudControls.REPEAT,
                38,
                22,
                hover,
                ready && song.canRepeat,
                !song.repeat.equals("none"),
                song.repeat.equals("track") ? "R1" : "R");
            button(font, SpotifyHudControls.PREVIOUS, 76, 24, hover, ready, false, "");
            button(font, SpotifyHudControls.PLAY, 108, 24, hover, ready, ready, "");
            button(font, SpotifyHudControls.NEXT, 140, 24, hover, ready, false, "");
            int ink = ready ? 0xDDF3E5 : 0x53645A;
            Gui.drawRect(84, 104, 86, 112, 0xFF000000 | ink);
            triangle(94, 103, 87, 108, 94, 113, ink);
            Gui.drawRect(154, 104, 156, 112, 0xFF000000 | ink);
            triangle(146, 103, 153, 108, 146, 113, ink);
            if (song.playing && ready) {
                Gui.drawRect(116, 103, 119, 113, 0xFF102719);
                Gui.drawRect(122, 103, 125, 113, 0xFF102719);
            } else triangle(117, 102, 125, 108, 117, 114, ready ? 0x102719 : ink);
            int volume = ready ? ClientSpotify.INPUT.volume(song, now) : 50;
            Gui.drawRect(177, 105, 180, 111, volume == 0 ? 0xFF6A7E71 : 0xFFC7DDCF);
            triangle(185, 102, 179, 108, 185, 114, volume == 0 ? 0x6A7E71 : 0xC7DDCF);
            if (volume == 0) font.drawStringWithShadow("x", 187, 104, 0x6A7E71);
            String level = volume < 0 ? "—" : volume + "%";
            font.drawStringWithShadow(level, 282 - font.getStringWidth(level), 94, 0x8FA99A);
            bar(
                SpotifyHudControls.VOLUME_LEFT,
                SpotifyHudControls.VOLUME_RIGHT,
                SpotifyHudControls.VOLUME_Y,
                Math.max(0, volume) / 100.0,
                hover == SpotifyHudControls.VOLUME,
                ready && song.volume >= 0);
            if (editing) {
                Gui.drawRect(0, 0, SpotifyHudLayout.WIDTH, 1, 0xFF6CE599);
                Gui.drawRect(
                    0,
                    SpotifyHudLayout.HEIGHT - 1,
                    SpotifyHudLayout.WIDTH,
                    SpotifyHudLayout.HEIGHT,
                    0xFF6CE599);
                Gui.drawRect(
                    SpotifyHudLayout.WIDTH - 1,
                    0,
                    SpotifyHudLayout.WIDTH,
                    SpotifyHudLayout.HEIGHT,
                    0xFF6CE599);
                for (int i = 0; i < 3; i++) {
                    int d = 3 + i * 3;
                    Gui.drawRect(
                        SpotifyHudLayout.WIDTH - d - 1,
                        SpotifyHudLayout.HEIGHT - 4,
                        SpotifyHudLayout.WIDTH - d + 1,
                        SpotifyHudLayout.HEIGHT - 2,
                        0xFFE7F7ED);
                    Gui.drawRect(
                        SpotifyHudLayout.WIDTH - 4,
                        SpotifyHudLayout.HEIGHT - d - 1,
                        SpotifyHudLayout.WIDTH - 2,
                        SpotifyHudLayout.HEIGHT - d + 1,
                        0xFFE7F7ED);
                }
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private static void button(FontRenderer font, int id, int x, int width, int hover, boolean enabled, boolean active,
        String label) {
        int color = !enabled ? 0xFF18231C : active ? 0xFF1ED760 : hover == id ? 0xFF3A5343 : 0xFF26372C;
        Gui.drawRect(x, 98, x + width, 118, color);
        if (!label.isEmpty()) font.drawStringWithShadow(
            label,
            x + (width - font.getStringWidth(label)) / 2,
            104,
            !enabled ? 0x53645A : active ? 0x102719 : 0xC5DCCC);
    }

    private static void bar(int left, int right, int y, double fraction, boolean hover, boolean enabled) {
        int end = left + (int) Math.round((right - left) * Math.max(0, Math.min(1, fraction)));
        Gui.drawRect(left, y - 1, right, y + 2, 0xFF34453B);
        Gui.drawRect(left, y - 1, end, y + 2, enabled ? 0xFF1ED760 : 0xFF52675A);
        if (enabled && hover) Gui.drawRect(end - 2, y - 3, end + 2, y + 4, 0xFFF0FFF5);
    }

    private static void triangle(int x1, int y1, int x2, int y2, int x3, int y3, int color) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_TRIANGLES);
        t.setColorOpaque_I(color);
        t.addVertex(x1, y1, 0);
        t.addVertex(x2, y2, 0);
        t.addVertex(x3, y3, 0);
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private static void line(FontRenderer font, String key, String value, int y, int color, long elapsed) {
        String label = I18n.format(key) + ": ";
        font.drawStringWithShadow(label, 84, y, 0x7F9C8A);
        int x = 84 + font.getStringWidth(label);
        font.drawStringWithShadow(scroll(font, value, SpotifyHudLayout.WIDTH - 12 - x, elapsed), x, y, color);
    }

    private static String scroll(FontRenderer font, String text, int width, long elapsed) {
        if (font.getStringWidth(text) <= width) return text;
        String loop = text + "   /   ";
        int start = Math.max(0, (int) (Math.max(0, elapsed) / 220 % (loop.length() + 10)) - 10);
        return font.trimStringToWidth((loop + text).substring(start), width);
    }
}
