package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import coint.events.DimensionUnloader;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandUnloadDimensions extends CommandBase {

    private final static String PERM_UNLOAD_DIMS = "cointcore.command.unloaddims";

    public CommandUnloadDimensions() {
        PermissionAPI
            .registerNode(PERM_UNLOAD_DIMS, DefaultPermissionLevel.OP, "CointCore manual dimension unload command");
    }

    @Override
    public String getCommandName() {
        return "unloaddims";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/unloaddims";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERM_UNLOAD_DIMS);
        }
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        int unloaded = DimensionUnloader.unloadEmptyDimensions();
        ChatComponentText msg = new ChatComponentText("Поставлено на выгрузку измерений: " + unloaded);
        msg.getChatStyle()
            .setColor(EnumChatFormatting.GREEN);
        sender.addChatMessage(msg);
    }
}
