package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import coint.integration.serverutilities.SURanksManager;
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
        if (args.length != 1 || (!"true".equals(args[0]) && !"false".equals(args[0]))) {
            sendError(sender, "Usage: /coint_sync <true|false>");
            return;
        }

        SURanksManager manager = SURanksManager.getInstance();
        if (manager == null) {
            sendError(sender, "SURanksManager not initialized");
            return;
        }

        boolean onlyRoles = parseBoolean(sender, args[0]);
        manager.syncRanks(onlyRoles);

        ChatComponentText success = new ChatComponentText("Sync initiated. Check server logs for results.");
        success.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GREEN));
        sender.addChatMessage(success);
    }

    private void sendError(ICommandSender sender, String message) {
        ChatComponentText msg = new ChatComponentText(message);
        msg.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED));
        sender.addChatMessage(msg);
    }
}
