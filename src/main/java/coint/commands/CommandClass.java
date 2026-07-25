package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import coint.CointCore;
import serverutils.lib.data.Universe;

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
        if (sender instanceof EntityPlayerMP p) {
            return Universe.get()
                .getPlayer(p)
                .isOP();
        }
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            try {
                var clazz = Class.forName(args[0]);
                String classResource = clazz.getName()
                    .replace('.', '/') + ".class";
                var l = clazz.getClassLoader() != null ? clazz.getClassLoader()
                    .getResource(classResource) : ClassLoader.getSystemResource(classResource);
                sender.addChatMessage(new ChatComponentText("url: " + l));
                CointCore.LOG.info("url: {}", l);
            } catch (ClassNotFoundException e) {
                throw new CommandException(e.getMessage());
            }
        }
    }
}
