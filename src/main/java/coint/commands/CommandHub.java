package coint.commands;

import java.io.IOException;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;

import com.neovisionaries.ws.client.WebSocketException;

import coint.http.HubWebSocket;

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
        return "subcommands: ws-recreate, ws-close";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        switch (args[0]) {
            case "ws-recreate": {
                try {
                    HubWebSocket.get()
                        .recreate();
                } catch (IOException | WebSocketException e) {
                    throw new RuntimeException(e);
                }
                return;
            }
            case "ws-close": {
                HubWebSocket.get()
                    .closeNormal("Closed by " + sender.getCommandSenderName());
                return;
            }
            default: {
                throw new WrongUsageException(getCommandUsage(sender));
            }
        }
    }
}
