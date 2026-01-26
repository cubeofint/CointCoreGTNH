package coint.epochsync;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import serverutils.lib.data.ForgePlayer;

public class CommandSync extends CommandBase {

    @Override
    public String getCommandName() {
        return "coint_sync";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender.getCommandSenderName()
            .equals("Rcon")) return true;

        ForgePlayer p = SURanksManager.INST.ranksInst.universe.getPlayer(sender);
        return p.isOP() || p.hasPermission("command.betterquesting.bq_admin");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/coint_sync true/false";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length != 1 || !(args[0].equals("true") || args[0].equals("false"))) {
            ChatComponentText msg = new ChatComponentText("Wrong argument. true/false only");
            msg.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED));
            sender.addChatMessage(msg);
        }
        boolean onlyRoles = parseBoolean(sender, args[0]);
        SURanksManager.INST.syncRanks(onlyRoles);
    }
}
