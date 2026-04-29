package coint.commands.spy;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * {@code /dmspy} — toggles in-game DM spy mode for the issuing administrator.
 *
 * <p>
 * While enabled, the admin receives a dimmed {@code [SPY]} copy of every private message sent by
 * any player, without the actual participants knowing. The state is in-memory only and resets on
 * server restart / relog.
 *
 * <p>
 * Required permission node: {@value #PERMISSION} (OP level by default).
 */
public class CommandDmSpy extends CommandBase {

    public static final String PERMISSION = "cointcore.dmspy";

    public CommandDmSpy() {
        PermissionAPI.registerNode(PERMISSION, DefaultPermissionLevel.OP, "Toggle in-game DM spy mode");
    }

    @Override
    public String getCommandName() {
        return "dmspy";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERMISSION);
        }
        return true; // console / RCON
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/dmspy — переключить режим шпионажа за личной перепиской";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        boolean enabled = PersonalSpyRegistry.toggle(sender.getCommandSenderName());

        ChatComponentText msg = new ChatComponentText(
            PersonalSpyRegistry.SPY_PREFIX
                + (enabled ? EnumChatFormatting.GREEN + "Включён — вы видите личную переписку игроков."
                    : EnumChatFormatting.RED + "Выключен."));
        sender.addChatMessage(msg);
    }
}
