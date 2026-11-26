package coint.integration.serverutilities;

import com.mojang.authlib.GameProfile;

import coint.cluster.ClusterHub;
import coint.cluster.ClusterService;
import cpw.mods.fml.common.eventhandler.Event;
import serverutils.ranks.Ranks;

/** The rank value "hub" scopes selected building tools without changing the replicated rank definition. */
public final class HubToolPermissions {

    private HubToolPermissions() {}

    public static Event.Result resolve(GameProfile player, String node, boolean checkPrefixes) {
        if (player == null || player.getId() == null
            || node == null
            || !supported(node)
            || Ranks.INSTANCE == null
            || !Ranks.isActive()) return Event.Result.DEFAULT;
        String value = Ranks.INSTANCE.getPlayerRank(player)
            .getPermission(node, node, checkPrefixes);
        if (!"hub".equalsIgnoreCase(value.trim())) return Event.Result.DEFAULT;
        return ClusterService.isRunning() && ClusterHub.isHub() ? Event.Result.ALLOW : Event.Result.DENY;
    }

    private static boolean supported(String node) {
        return node.equals("command.gamemode") || node.equals("command.minecraft.gamemode")
            || node.equals("command.worldedit")
            || node.startsWith("command.worldedit.")
            || node.equals("worldedit")
            || node.startsWith("worldedit.");
    }
}
