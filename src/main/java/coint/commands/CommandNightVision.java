package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ChatComponentText;

import serverutils.ranks.Ranks;

public class CommandNightVision extends CommandBase {

    private static final int INFINITE_DURATION = Integer.MAX_VALUE;

    @Override
    public String getCommandName() {
        return "nv";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return Ranks.INSTANCE.getPermission(player.getGameProfile(), "command.cointcore.nv", false)
                .getBoolean();
        }

        sender.addChatMessage(new ChatComponentText("Console cannot use night vision"));
        return false;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/nv";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        EntityPlayer player = (EntityPlayer) sender;
        if (player.isPotionActive(Potion.nightVision)) {
            player.removePotionEffect(Potion.nightVision.id);
        } else {
            player.addPotionEffect(new PotionEffect(Potion.nightVision.id, INFINITE_DURATION, 0, true));
        }
    }
}
