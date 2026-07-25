package coint.util;

import coint.events.KeepInventoryHandler;
import coint.integration.serverutilities.CointSUPermissions;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class PermissionsUtil {

    public static void register() {
        PermissionAPI.registerNode(
            KeepInventoryHandler.PERMISSION,
            DefaultPermissionLevel.NONE,
            "Сохранять инвентарь при смерти");

        // Split /god, /fly, /tpl into self-use vs. targeting-others tiers.
        // The existing SU node "commands.<name>" controls self-use (unchanged).
        // These new nodes guard the "apply to another player" variant.
        PermissionAPI.registerNode(
            CointSUPermissions.TP_COORDS,
            DefaultPermissionLevel.NONE,
            "Teleport to coordinates via /tp x y z (JourneyMap waypoints)");
        PermissionAPI.registerNode(
            CointSUPermissions.GOD_OTHER,
            DefaultPermissionLevel.OP,
            "Apply god mode to another player via /god <player>");
        PermissionAPI.registerNode(
            CointSUPermissions.FLY_OTHER,
            DefaultPermissionLevel.OP,
            "Toggle fly for another player via /fly <player>");
        PermissionAPI.registerNode(
            CointSUPermissions.TPL_OTHER,
            DefaultPermissionLevel.OP,
            "Teleport another player to someone via /tpl <who> <to>");
        PermissionAPI.registerNode(
            CointSUPermissions.TPL_TO_PROTECTED,
            DefaultPermissionLevel.OP,
            "Teleport to protected players via /tpl (e.g. admins)");
    }
}
