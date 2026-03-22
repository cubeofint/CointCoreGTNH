package coint.mixin.serverutilities;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;

import coint.integration.serverutilities.CointSUPermissions;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * Splits {@code /god} into two separate permission levels:
 * <ul>
 * <li>{@code commands.god} (existing SU node) - apply god mode to <b>yourself</b></li>
 * <li>{@code cointcore.god.other} - apply god mode to <b>another player</b></li>
 * </ul>
 *
 * <p>
 * When {@code /god <player>} is used, the sender must also hold the
 * {@code cointcore.god.other} permission. Without it the command is still usable
 * on the sender themselves (no argument form). Console/RCON always passes through.
 *
 * @deprecated Replaced by {@link coint.integration.serverutilities.CointCommandGuard}.
 *             See {@link coint.mixin.serverutilities.MixinCmdFly} for the full
 *             explanation of why the {@code remap = false} + {@code processCommand}
 *             approach silently fails at runtime. This class is kept for historical
 *             reference only and is <em>not</em> listed in {@code mixins.cointcore.json}.
 */
@Deprecated
public class MixinCmdGod {

    // Historical injection target: CmdGod.processCommand - no longer active.
    @SuppressWarnings("unused")
    private void cointcore$checkGodOther(ICommandSender sender, String[] args) {
        if (args.length > 0 && sender instanceof EntityPlayerMP playerMP
            && !PermissionAPI.hasPermission(playerMP, CointSUPermissions.GOD_OTHER)) {
            cointcore$sneakyThrow(new CommandException("commands.generic.permission"));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void cointcore$sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    /** Prevents instantiation - historical reference class only. */
    private MixinCmdGod() {}
}
