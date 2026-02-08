package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

import serverutils.ranks.Ranks;

public class CommandHeal extends CommandBase {

    @Override
    public String getCommandName() {
        return "heal";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return Ranks.INSTANCE.getPermission(player.getGameProfile(), "coint.heal", false)
                .getBoolean();
        } else {
            sender.addChatMessage(new ChatComponentText("Console cannot be healed"));
            return false;
        }
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/heal";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        EntityPlayer player = (EntityPlayer) sender;
        player.heal(Float.MAX_VALUE);
    }
}
