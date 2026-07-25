package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * Command to sync ranks with external API.
 */
public class CommandSync extends CommandBase {

    public static final String PERMISSION = "cointcore.command.sync";

    public CommandSync() {
        PermissionAPI.registerNode(PERMISSION, DefaultPermissionLevel.OP, "CointCore sync ranks permission");
    }

    @Override
    public String getCommandName() {
        return "coint_sync";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof net.minecraft.entity.player.EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERMISSION);
        }
        return true; // console/RCON
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/coint_sync <true|false> - Sync ranks (true = only roles, false = roles + players)";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        // TODO?: add api syncing
        throw new WrongUsageException("Work in progress");
    }

    private void sendError(ICommandSender sender, String message) {
        ChatComponentText msg = new ChatComponentText(message);
        msg.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED));
        sender.addChatMessage(msg);
    }
}
