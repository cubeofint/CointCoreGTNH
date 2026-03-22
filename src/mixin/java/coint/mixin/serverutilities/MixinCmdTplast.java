package coint.mixin.serverutilities;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import coint.integration.serverutilities.CointSUPermissions;
import serverutils.lib.util.permission.PermissionAPI;
/**
 * Splits {@code /tpl} into two separate permission levels:
 * <ul>
 * <li>{@code commands.serverutilities.tpl} (existing SU node) - teleport <b>yourself</b>
 * to a player</li>
 * <li>{@code cointcore.tpl.other} - teleport <b>another player</b> to someone
 * ({@code /tpl <who> <to>})</li>
 * </ul>
 *
 * <p>
 * The "other" branch is only the two-argument form {@code /tpl <who> <to>}. Single-argument
 * {@code /tpl <player>} and coordinate form {@code /tpl <x> <y> <z>} are unaffected.
 *
 * @deprecated Replaced by {@link coint.integration.serverutilities.CointCommandGuard}.
 *             See {@link coint.mixin.serverutilities.MixinCmdFly} for the full
 *             explanation of why the {@code remap = false} + {@code processCommand}
 *             approach silently fails at runtime. This class is kept for historical
 *             reference only and is <em>not</em> listed in {@code mixins.cointcore.json}.
 */
@Deprecated
public class MixinCmdTplast {
    // Historical injection target: CmdTplast.processCommand - no longer active.
    @SuppressWarnings("unused")
    private void cointcore$checkTplOther(ICommandSender sender, String[] args) {
        if (args.length == 2 && sender instanceof EntityPlayerMP playerMP
            && !PermissionAPI.hasPermission(playerMP, CointSUPermissions.TPL_OTHER)) {
            cointcore$sneakyThrow(new CommandException("commands.generic.permission"));
        }
    }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void cointcore$sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
    /** Prevents instantiation - historical reference class only. */
    private MixinCmdTplast() {}
}