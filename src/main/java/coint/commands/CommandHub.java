package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import coint.config.CointConfig;
import coint.core.ChatWSClient;

public class CommandHub extends CommandBase {

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return !(sender instanceof EntityPlayerMP);
    }

    @Override
    public String getCommandName() {
        return "hub";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/hub reconnect";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        switch (args[0]) {
            case "reconnect": {
                if (!CointConfig.wsHubEnabled) {
                    sender.addChatMessage(
                        new ChatComponentText(
                            EnumChatFormatting.RED + "WebSocket-хаб отключён в cointcore.cfg (wsHubEnabled=false)"));
                    return;
                }
                if (ChatWSClient.inst == null) {
                    ChatWSClient.init();
                    sender.addChatMessage(
                        new ChatComponentText(
                            EnumChatFormatting.YELLOW
                                + "Клиент хаба не был создан; вызван init (проверьте wsHubUrl и лог)"));
                    return;
                }
                ChatWSClient.inst.reconnect();
                sender.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.GREEN + "Запрошено переподключение к WebSocket-хабу"));
                break;
            }
            case "":
            default: {
                throw new WrongUsageException(getCommandUsage(sender));
            }
        }
    }
}
