package coint.commands;

import coint.CointCore;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import org.java_websocket.client.WebSocketClient;
import serverutils.lib.data.Universe;

import java.net.URL;

public class CommandClass extends CommandBase {

    @Override
    public String getCommandName() {
        return "class";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/class";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        var p = Universe.get().getPlayer(sender);
        if (p != null) {
            return p.isOP();
        }
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        Class<WebSocketClient> clazz = WebSocketClient.class;
        URL l = clazz.getProtectionDomain().getCodeSource().getLocation();
        if (l != null) {
            sender.addChatMessage(new ChatComponentText("url: " + l.getFile()));
            CointCore.LOG.info("url: {}", l.getFile());
            return;
        } else {
            String classResource = clazz.getName().replace('.', '/') + ".class";
            l = clazz.getClassLoader() != null
                ? clazz.getClassLoader().getResource(classResource)
                : ClassLoader.getSystemResource(classResource);
        }
        sender.addChatMessage(new ChatComponentText("url: " + l));
        CointCore.LOG.info("url: {}", l);
    }
}
