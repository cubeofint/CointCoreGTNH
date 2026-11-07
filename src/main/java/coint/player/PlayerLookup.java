package coint.player;

import java.util.UUID;

import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;

/**
 * Players by the name a moderator typed. ServerUtilities' own lookup falls back to any name that merely contains the
 * text ("Dev" finds "Developer"), so a ban, reset or mute could hit the wrong player: here only the whole name (any
 * case) or the UUID counts.
 */
public final class PlayerLookup {

    private PlayerLookup() {}

    /** The player with exactly this name or UUID, or null. */
    public static ForgePlayer exact(CharSequence nameOrId) {
        if (nameOrId == null) {
            return null;
        }
        String s = nameOrId.toString()
            .trim();
        if (s.isEmpty()) {
            return null;
        }
        Universe universe = Universe.get();
        if (s.length() == 36 && s.indexOf('-') > 0) {
            try {
                return universe.getPlayer(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // Not a UUID: a name after all.
            }
        }
        for (ForgePlayer player : universe.getPlayers()) {
            if (player.getName()
                .equalsIgnoreCase(s)) {
                return player;
            }
        }
        return null;
    }
}
