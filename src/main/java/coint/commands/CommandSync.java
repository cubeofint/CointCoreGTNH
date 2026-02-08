package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import coint.integration.serverutilities.SURanksManager;
import serverutils.lib.data.ForgePlayer;

/**
 * Command to sync ranks with external API.
 */
public class CommandSync extends CommandBase {

    @Override
    public String getCommandName() {
        return "coint_sync";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        // Allow RCON
        if ("Rcon".equals(sender.getCommandSenderName())) {
            return true;
        }

        // Check player permissions
        SURanksManager manager = SURanksManager.getInstance();
        if (manager == null || !manager.isReady()) {
            return false;
        }

        var universe = manager.getUniverse();
        if (universe == null) {
            return false;
        }

        ForgePlayer player = universe.getPlayer(sender);
        if (player == null) {
            return false;
        }

        // TODO: change permission
        return player.isOP() || player.hasPermission("command.betterquesting.bq_admin");
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

        sendSuccess(sender, "Sync initiated. Check server logs for results.");
    }

    private void sendError(ICommandSender sender, String message) {
        ChatComponentText msg = new ChatComponentText(message);
        msg.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED));
        sender.addChatMessage(msg);
    }

    private void sendSuccess(ICommandSender sender, String message) {
        ChatComponentText msg = new ChatComponentText(message);
        msg.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GREEN));
        sender.addChatMessage(msg);
    }
}
