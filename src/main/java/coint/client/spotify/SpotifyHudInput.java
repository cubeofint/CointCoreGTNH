package coint.client.spotify;

import java.util.function.Consumer;

/** Chat mouse capture: never consumes typing or mouse events outside the widget. */
final class SpotifyHudInput {

    private final Consumer<String> commands;
    private final Consumer<SpotifyHudLayout> save;
    private int gesture = SpotifyHudControls.NONE;
    private int requestedVolume = -1, unmutedVolume = 50;
    private double seek;
    private String seekTrack = "";
    private long volumeAt, sentAt;

    SpotifyHudInput(Consumer<String> commands, Consumer<SpotifyHudLayout> save) {
        this.commands = commands;
        this.save = save;
    }

    boolean mouse(SpotifyHudLayout layout, SpotifyBridge.Snapshot song, int mx, int my, int button, boolean down,
        int wheel, int width, int height, long now) {
        if (!"ready".equals(song.status)) {
            finish(layout, song, now);
            return false;
        }
        SpotifyHudLayout.Bounds box = layout.bounds(width, height);
        int hit = SpotifyHudControls.hit(box, mx, my);
        boolean captured = gesture != SpotifyHudControls.NONE;
        if (captured) {
            drag(layout, box, mx, my, width, height, now);
            if (button == 0 && !down) finish(layout, song, now);
            return true;
        }
        if (hit == SpotifyHudControls.NONE) return false;
        if (wheel != 0) {
            if ((hit == SpotifyHudControls.VOLUME || hit == SpotifyHudControls.MUTE) && song.volume >= 0) {
                requestedVolume = Math.max(0, Math.min(100, volume(song, now) + (wheel > 0 ? 5 : -5)));
                sendVolume(now);
            } else if (layout.zoom(mx, my, wheel, width, height)) save.accept(layout);
            return true;
        }
        if (button != 0 || !down) return true;
        switch (hit) {
            case SpotifyHudControls.MOVE, SpotifyHudControls.RESIZE -> {
                gesture = hit;
                layout.begin(mx, my, width, height);
            }
            case SpotifyHudControls.PREVIOUS -> commands.accept("previous");
            case SpotifyHudControls.PLAY -> commands.accept("toggle");
            case SpotifyHudControls.NEXT -> commands.accept("next");
            case SpotifyHudControls.SHUFFLE -> {
                if (song.canShuffle) commands.accept("shuffle");
            }
            case SpotifyHudControls.REPEAT -> {
                if (song.canRepeat) commands.accept("repeat");
            }
            case SpotifyHudControls.MUTE -> {
                if (song.volume >= 0) {
                    int level = volume(song, now);
                    if (level > 0) unmutedVolume = level;
                    requestedVolume = level > 0 ? 0 : unmutedVolume;
                    sendVolume(now);
                }
            }
            case SpotifyHudControls.SEEK -> {
                if (song.duration > 0 && song.canSeek) {
                    gesture = hit;
                    seekTrack = track(song);
                    seek = SpotifyHudControls
                        .fraction(box, mx, SpotifyHudControls.SEEK_LEFT, SpotifyHudControls.SEEK_RIGHT);
                }
            }
            case SpotifyHudControls.VOLUME -> {
                if (song.volume >= 0) {
                    gesture = hit;
                    requestedVolume = (int) Math.round(
                        100 * SpotifyHudControls
                            .fraction(box, mx, SpotifyHudControls.VOLUME_LEFT, SpotifyHudControls.VOLUME_RIGHT));
                    sendVolume(now);
                }
            }
            default -> {}
        }
        return true;
    }

    private void drag(SpotifyHudLayout layout, SpotifyHudLayout.Bounds box, int mx, int my, int width, int height,
        long now) {
        if (gesture == SpotifyHudControls.MOVE || gesture == SpotifyHudControls.RESIZE)
            layout.drag(mx, my, width, height);
        else if (gesture == SpotifyHudControls.VOLUME) {
            requestedVolume = (int) Math.round(
                100 * SpotifyHudControls
                    .fraction(box, mx, SpotifyHudControls.VOLUME_LEFT, SpotifyHudControls.VOLUME_RIGHT));
            volumeAt = now;
            if (now - sentAt >= 100) sendVolume(now);
        } else if (gesture == SpotifyHudControls.SEEK)
            seek = SpotifyHudControls.fraction(box, mx, SpotifyHudControls.SEEK_LEFT, SpotifyHudControls.SEEK_RIGHT);
    }

    void finish(SpotifyHudLayout layout, SpotifyBridge.Snapshot song, long now) {
        if (gesture == SpotifyHudControls.SEEK && seekTrack.equals(track(song)) && song.canSeek)
            commands.accept("seek " + (long) (seek * song.duration));
        if (gesture == SpotifyHudControls.VOLUME) sendVolume(now);
        gesture = SpotifyHudControls.NONE;
        if (layout.end()) save.accept(layout);
    }

    private void sendVolume(long now) {
        volumeAt = now;
        sentAt = now;
        commands.accept("volume " + requestedVolume);
    }

    int volume(SpotifyBridge.Snapshot song, long now) {
        return requestedVolume >= 0 && now - volumeAt < 1500 ? requestedVolume : song.volume;
    }

    long position(SpotifyBridge.Snapshot song) {
        return gesture == SpotifyHudControls.SEEK && seekTrack.equals(track(song)) ? (long) (seek * song.duration)
            : song.positionNow();
    }

    private static String track(SpotifyBridge.Snapshot song) {
        return song.title + "\n" + song.artist + "\n" + song.duration;
    }
}
