package coint.client.spotify;

/** Shared local coordinates for rendering and hit testing at every widget scale. */
final class SpotifyHudControls {

    static final int NONE = -1, MOVE = 0, RESIZE = 1, PREVIOUS = 2, PLAY = 3, NEXT = 4, SHUFFLE = 5, REPEAT = 6,
        MUTE = 7, SEEK = 8, VOLUME = 9;
    static final int SEEK_LEFT = 84, SEEK_RIGHT = 288, SEEK_Y = 82;
    static final int VOLUME_LEFT = 198, VOLUME_RIGHT = 282, VOLUME_Y = 108;

    private SpotifyHudControls() {}

    static int hit(SpotifyHudLayout.Bounds box, int mx, int my) {
        if (!box.contains(mx, my)) return NONE;
        if (box.resizeHandle(mx, my)) return RESIZE;
        double x = (mx - box.x) / box.scale, y = (my - box.y) / box.scale;
        if (y >= 98 && y < 118) {
            if (x >= 10 && x < 32) return SHUFFLE;
            if (x >= 38 && x < 60) return REPEAT;
            if (x >= 76 && x < 100) return PREVIOUS;
            if (x >= 108 && x < 132) return PLAY;
            if (x >= 140 && x < 164) return NEXT;
            if (x >= 174 && x < 194) return MUTE;
            if (x >= VOLUME_LEFT - 3 && x <= VOLUME_RIGHT + 3) return VOLUME;
        }
        if (x >= SEEK_LEFT && x <= SEEK_RIGHT && y >= SEEK_Y - 5 && y <= SEEK_Y + 6) return SEEK;
        return MOVE;
    }

    static double fraction(SpotifyHudLayout.Bounds box, int mx, int left, int right) {
        return Math.max(0, Math.min(1, ((mx - box.x) / box.scale - left) / (right - left)));
    }
}
