package coint.integration.serverutilities;

/**
 * Permission node constants introduced by CointCore for ServerUtilities commands.
 *
 * <p>
 * These nodes split existing single-permission commands into two tiers:
 * <ol>
 * <li>The vanilla SU {@code commands.<name>} node controls access to the command itself
 * (i.e. the player can use it on <b>themselves</b>).</li>
 * <li>The nodes below additionally guard the <b>"target another player"</b> variant.</li>
 * </ol>
 *
 * <p>
 * All nodes default to {@code OP} so existing operator setups are not affected.
 */
public final class CointSUPermissions {

    private CointSUPermissions() {}

    /**
     * Allows the player to apply god mode to <b>another</b> player via {@code /god <player>}.
     * Without this permission the player may still use {@code /god} on themselves.
     */
    public static final String GOD_OTHER = "cointcore.god.other";

    /**
     * Allows the player to toggle fly for <b>another</b> player via {@code /fly <player>}.
     * Without this permission the player may still use {@code /fly} on themselves.
     */
    public static final String FLY_OTHER = "cointcore.fly.other";

    /**
     * Allows the player to teleport to coordinates via the {@code /tp x y z} alias
     * (used by JourneyMap waypoints). Does <em>not</em> require the full
     * {@code commands.serverutilities.tpl} permission — this is a narrower grant
     * specifically for minimap/waypoint coordinate teleportation.
     */
    public static final String TP_COORDS = "cointcore.tp.coords";

    /**
     * Allows the player to teleport to <b>another</b> player via {@code /tpl <who> <to>}.
     * Without this permission the player may still use {@code /tpl <player>} to teleport themselves.
     */
    public static final String TPL_OTHER = "cointcore.tpl.other";
}
