package coint.client.spotify;

/** Relative placement keeps the widget visible after changing the window size or GUI scale. */
final class SpotifyHudLayout {

    static final int WIDTH = 300;
    static final int HEIGHT = 128;
    static final double MIN_SCALE = 0.65;
    static final double MAX_SCALE = 2.0;
    private static final int MARGIN = 4;

    static final class Bounds {

        final int x, y, width, height;
        final float scale;

        Bounds(int x, int y, float scale) {
            this.x = x;
            this.y = y;
            this.scale = scale;
            width = (int) Math.ceil(WIDTH * scale);
            height = (int) Math.ceil(HEIGHT * scale);
        }

        boolean contains(int mx, int my) {
            return mx >= x && my >= y && mx < x + width && my < y + height;
        }

        boolean resizeHandle(int mx, int my) {
            return contains(mx, my) && mx >= x + width - 14 && my >= y + height - 14;
        }
    }

    double x, y, scale;
    private int gesture;
    private int offsetX, offsetY, anchorX, anchorY;

    SpotifyHudLayout(double x, double y, double scale) {
        this.x = clamp(x, 0, 1, 1);
        this.y = clamp(y, 0, 1, 0);
        this.scale = clamp(scale, MIN_SCALE, MAX_SCALE, 1);
    }

    SpotifyHudLayout copy() {
        return new SpotifyHudLayout(x, y, scale);
    }

    Bounds bounds(int screenWidth, int screenHeight) {
        float fitted = (float) Math.min(
            scale,
            Math.min(
                Math.max(1, screenWidth - MARGIN * 2) / (double) WIDTH,
                Math.max(1, screenHeight - MARGIN * 2) / (double) HEIGHT));
        int w = (int) Math.ceil(WIDTH * fitted), h = (int) Math.ceil(HEIGHT * fitted);
        return new Bounds(
            MARGIN + (int) Math.round(x * Math.max(0, screenWidth - MARGIN * 2 - w)),
            MARGIN + (int) Math.round(y * Math.max(0, screenHeight - MARGIN * 2 - h)),
            fitted);
    }

    boolean begin(int mx, int my, int screenWidth, int screenHeight) {
        Bounds box = bounds(screenWidth, screenHeight);
        if (!box.contains(mx, my)) return false;
        gesture = box.resizeHandle(mx, my) ? 2 : 1;
        offsetX = mx - box.x;
        offsetY = my - box.y;
        anchorX = box.x;
        anchorY = box.y;
        return true;
    }

    void drag(int mx, int my, int screenWidth, int screenHeight) {
        if (gesture == 1) place(mx - offsetX, my - offsetY, screenWidth, screenHeight);
        else if (gesture == 2) {
            double wanted = ((mx - anchorX) * (double) WIDTH + (my - anchorY) * (double) HEIGHT)
                / (WIDTH * WIDTH + HEIGHT * HEIGHT);
            resize(wanted, screenWidth, screenHeight);
            place(anchorX, anchorY, screenWidth, screenHeight);
        }
    }

    boolean zoom(int mx, int my, int delta, int screenWidth, int screenHeight) {
        Bounds box = bounds(screenWidth, screenHeight);
        if (delta == 0 || !box.contains(mx, my)) return false;
        resize(box.scale + (delta > 0 ? 0.1 : -0.1), screenWidth, screenHeight);
        place(box.x, box.y, screenWidth, screenHeight);
        return true;
    }

    private void resize(double wanted, int screenWidth, int screenHeight) {
        double fit = Math
            .min((screenWidth - MARGIN * 2) / (double) WIDTH, (screenHeight - MARGIN * 2) / (double) HEIGHT);
        scale = clamp(wanted, Math.min(MIN_SCALE, fit), Math.min(MAX_SCALE, fit), 1);
    }

    private void place(int left, int top, int screenWidth, int screenHeight) {
        Bounds box = bounds(screenWidth, screenHeight);
        x = clamp((left - MARGIN) / (double) Math.max(1, screenWidth - MARGIN * 2 - box.width), 0, 1, 1);
        y = clamp((top - MARGIN) / (double) Math.max(1, screenHeight - MARGIN * 2 - box.height), 0, 1, 0);
    }

    boolean interacting() {
        return gesture != 0;
    }

    boolean end() {
        boolean changed = interacting();
        gesture = 0;
        return changed;
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
