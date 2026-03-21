package coint.commands;

import java.util.Collections;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import coint.integration.serverutilities.CointSUPermissions;
import serverutils.lib.command.CommandUtils;
import serverutils.lib.math.BlockDimPos;
import serverutils.lib.math.TeleporterDimPos;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * Custom {@code /tp} handler that introduces a lightweight coordinate-teleport
 * permission tier on top of the standard OP requirement.
 *
 * <p>
 * Permission model:
 * <ul>
 * <li>{@code cointcore.tp.coords} — may use {@code /tp x y z} (the form
 * JourneyMap sends when a player clicks a waypoint). Relative coordinates
 * ({@code ~}) are supported.</li>
 * <li>OP (level 2) — full access: all forms including {@code /tp <player>}
 * and {@code /tp <from> <to>}.</li>
 * </ul>
 *
 * <p>
 * This command shadows (replaces) vanilla {@code /tp} because Forge's
 * {@link cpw.mods.fml.common.event.FMLServerStartingEvent} registration
 * overwrites the entry in the command map for the same name.
 */
public final class CommandTpAlias extends CommandBase {

    @Override
    public String getCommandName() {
        return "tp";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/tp <x> <y> <z>  |  /tp <player>  |  /tp <from> <to>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        // Keeps the ServerUtilities auto-registered node at OP level by default.
        return 2;
    }

    /**
     * Grants access when the sender either:
     * <ol>
     * <li>holds the {@code cointcore.tp.coords} permission (waypoint tp), or</li>
     * <li>is an operator (full tp access).</li>
     * </ol>
     * Console / RCON always passes through.
     *
     * <p>
     * Note: args are not available here. The narrowing to "coordinate form only"
     * for {@code TP_COORDS} holders is enforced inside {@link #processCommand}.
     */
    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (!(sender instanceof EntityPlayer player)) return true;
        return PermissionAPI.hasPermission(player, CointSUPermissions.TP_COORDS)
            || sender.canCommandSenderUseCommand(2, getCommandName());
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        if (args.length >= 4) {
            // ── /tp <player> <x> <y> <z> ───────────────────────────────────────
            // JourneyMap uses this 4-arg form. TP_COORDS is allowed only when the
            // target is the sender themselves; teleporting another player requires OP.
            EntityPlayerMP target = CommandUtils.getForgePlayer(sender, args[0])
                .getCommandPlayer(sender);
            boolean isSelf = sender instanceof EntityPlayerMP sp && sp.equals(target);
            if (!isSelf && sender instanceof EntityPlayer && !sender.canCommandSenderUseCommand(2, getCommandName())) {
                throw new CommandException("commands.generic.permission");
            }
            double x = func_110665_a(sender, target.posX, args[1], -30000000, 30000000);
            double y = func_110665_a(sender, target.posY, args[2], -30000000, 30000000);
            double z = func_110665_a(sender, target.posZ, args[3], -30000000, 30000000);
            TeleporterDimPos.of(x, y, z, target.dimension)
                .teleport(target);
            return;
        }

        if (args.length == 3) {
            // ── /tp <x> <y> <z> ────────────────────────────────────────────────
            // canCommandSenderUseCommand already confirmed TP_COORDS or OP.
            EntityPlayerMP player = getCommandSenderAsPlayer(sender);
            double x = func_110665_a(sender, player.posX, args[0], -30000000, 30000000);
            double y = func_110665_a(sender, player.posY, args[1], -30000000, 30000000);
            double z = func_110665_a(sender, player.posZ, args[2], -30000000, 30000000);
            TeleporterDimPos.of(x, y, z, player.dimension)
                .teleport(player);
            return;
        }

        // ── Player forms: /tp <player> or /tp <from> <to> ─────────────────────
        // These are OP-only even if the sender holds TP_COORDS.
        if (sender instanceof EntityPlayer && !sender.canCommandSenderUseCommand(2, getCommandName())) {
            throw new CommandException("commands.generic.permission");
        }

        if (args.length == 1) {
            // /tp <player> — teleport the command sender to the named player
            EntityPlayerMP self = getCommandSenderAsPlayer(sender);
            EntityPlayerMP target = CommandUtils.getForgePlayer(sender, args[0])
                .getCommandPlayer(sender);
            new BlockDimPos(target).teleporter()
                .teleport(self);
        } else {
            // /tp <from> <to> — teleport 'from' to 'to'
            EntityPlayerMP from = CommandUtils.getForgePlayer(sender, args[0])
                .getCommandPlayer(sender);
            EntityPlayerMP to = CommandUtils.getForgePlayer(sender, args[1])
                .getCommandPlayer(sender);
            new BlockDimPos(to).teleporter()
                .teleport(from);
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1 || args.length == 2) {
            return getListOfStringsMatchingLastWord(
                args,
                MinecraftServer.getServer()
                    .getAllUsernames());
        }
        return Collections.emptyList();
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        // Only the 1-arg and 2-arg (player) forms have username indices.
        return args.length <= 2 && index < 2;
    }
}
