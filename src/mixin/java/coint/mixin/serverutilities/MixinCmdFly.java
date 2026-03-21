package coint.mixin.serverutilities;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;

import coint.integration.serverutilities.CointSUPermissions;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * Splits {@code /fly} into two separate permission levels:
 * <ul>
 * <li>{@code commands.fly} (existing SU node) — toggle fly for <b>yourself</b></li>
 * <li>{@code cointcore.fly.other} — toggle fly for <b>another player</b></li>
 * </ul>
 *
 * <p>
 * When {@code /fly <player>} is used, the sender must also hold the
 * {@code cointcore.fly.other} permission. Without it the command is still usable
 * on the sender themselves (no argument form). Console/RCON always passes through.
 *
 * @deprecated Replaced by {@link coint.integration.serverutilities.CointCommandGuard}.
 *
 *             <p>
 *             ForgeGradle reobfuscates overrides of vanilla interface methods (such as
 *             {@code ICommand.processCommand}) from their MCP name to the SRG name
 *             (e.g. {@code func_71515_b}) in the distributed mod jar. Because this
 *             mixin used {@code remap = false}, the Mixin framework looked for the
 *             literal name {@code processCommand} at runtime and silently skipped the
 *             injection — so the permission check never fired regardless of rank
 *             configuration.
 *
 *             <p>
 *             The replacement uses Forge's {@code CommandEvent} which fires before
 *             {@code processCommand} and does not depend on bytecode method names.
 *             This class is kept for historical reference only and is <em>not</em>
 *             listed in {@code mixins.cointcore.json}.
 */
@Deprecated
public class MixinCmdFly {

    // Historical injection target: CmdFly.processCommand — no longer active.
    @SuppressWarnings("unused")
    private void cointcore$checkFlyOther(ICommandSender sender, String[] args) {
        if (args.length > 0 && sender instanceof EntityPlayerMP playerMP
            && !PermissionAPI.hasPermission(playerMP, CointSUPermissions.FLY_OTHER)) {
            cointcore$sneakyThrow(new CommandException("commands.generic.permission"));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void cointcore$sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    /** Prevents instantiation — historical reference class only. */
    private MixinCmdFly() {}
}
