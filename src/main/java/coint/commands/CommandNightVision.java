package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import coint.events.NightVisionHandler;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandNightVision extends CommandBase {

    public static final String PERMISSION = "cointcore.command.nv";

    public CommandNightVision() {
        PermissionAPI.registerNode(PERMISSION, DefaultPermissionLevel.OP, "CointCore night vision permission");
    }

    @Override
    public String getCommandName() {
        return "nv";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERMISSION);
        }
        return true; // console/RCON
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/nv";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP player)) {
            throw new WrongUsageException("Команда доступна только игроку");
        }
        // The toggle is the stored flag, not the potion: the client drops a long effect on its own after ~27 min
        // (packet duration is a short) while the server still has it, so checking the potion toggled the wrong way.
        boolean enable = !NightVisionHandler.isEnabled(player);
        NightVisionHandler.setEnabled(player, enable);
        sendSuccess(sender, enable ? "Ночное видение включено" : "Ночное видение выключено");
    }

    private void sendSuccess(ICommandSender sender, String message) {
        ChatComponentText msg = new ChatComponentText(message);
        msg.getChatStyle()
            .setColor(EnumChatFormatting.GREEN);
        sender.addChatMessage(msg);
    }
}
