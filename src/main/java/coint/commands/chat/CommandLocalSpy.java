package coint.commands.chat;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * {@code /localspy} — toggles local-chat spy mode for the issuing administrator.
 *
 * <p>
 * While enabled, the admin receives a {@code §8[§dЛОКАЛ§8]} copy of every local chat
 * message sent by any player that the admin <em>would not normally see</em> (i.e. the
 * sender was outside the admin's local-chat radius or in a different dimension). If the
 * admin is already within range they receive the message through the normal pipeline
 * and no duplicate is generated.
 *
 * <p>
 * Each spy copy includes the sender's dimension and block coordinates so the admin can
 * determine the context of the conversation.
 *
 * <p>
 * State is in-memory only and resets on server restart / relog.
 *
 * <p>
 * Required permission node: {@value #PERMISSION} (OP level by default).
 */
public class CommandLocalSpy extends CommandBase {

    public static final String PERMISSION = "cointcore.localspy";

    public CommandLocalSpy() {
        PermissionAPI.registerNode(PERMISSION, DefaultPermissionLevel.OP, "Toggle local-chat spy mode");
    }

    @Override
    public String getCommandName() {
        return "localspy";
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
        return "/localspy — переключить режим шпионажа за локальным чатом";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        boolean enabled = LocalSpyRegistry.toggle(sender.getCommandSenderName());

        ChatComponentText msg = new ChatComponentText(
            LocalSpyRegistry.SPY_PREFIX
                + (enabled ? EnumChatFormatting.GREEN + "Включён — вы видите локальные сообщения всех игроков."
                    : EnumChatFormatting.RED + "Выключен."));
        sender.addChatMessage(msg);
    }
}
