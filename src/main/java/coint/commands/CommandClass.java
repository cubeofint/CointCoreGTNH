package coint.commands;

import java.lang.reflect.Method;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import coint.CointCore;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandClass extends CommandBase {

    private final static String PERM_CLASS = "cointcore.command.class";

    public CommandClass() {
        PermissionAPI.registerNode(PERM_CLASS, DefaultPermissionLevel.OP, "CointCore reflective class inspection");
    }

    @Override
    public String getCommandName() {
        return "class";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/class <fqcn> [filter]";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERM_CLASS);
        }
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        String className = args[0];
        String filter = args.length > 1 ? args[1].toLowerCase() : null;

        Class<?> cls;
        try {
            cls = Class.forName(className, false, getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            send(sender, "Класс не найден: " + className, EnumChatFormatting.RED);
            return;
        }

        String classResource = cls.getName()
            .replace('.', '/') + ".class";
        var url = cls.getClassLoader() != null ? cls.getClassLoader()
            .getResource(classResource) : ClassLoader.getSystemResource(classResource);

        StringBuilder dump = new StringBuilder();
        dump.append("Inspecting ")
            .append(cls.getName())
            .append(" (loader: ")
            .append(cls.getClassLoader())
            .append(", url: ")
            .append(url)
            .append(")\n");

        int count = 0;
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (filter != null && !m.getName()
                    .toLowerCase()
                    .contains(filter)) continue;
                dump.append(c.getSimpleName())
                    .append("# ")
                    .append(m)
                    .append('\n');
                count++;
            }
        }

        CointCore.LOG.info("[InspectClass]\n{}", dump);

        String suffix = filter != null ? ", filter=" + filter : "";
        send(
            sender,
            "url: " + url
                + " | найдено "
                + count
                + " методов, полный список в логе сервера ("
                + className
                + suffix
                + ")",
            EnumChatFormatting.GREEN);
    }

    private void send(ICommandSender sender, String text, EnumChatFormatting color) {
        ChatComponentText msg = new ChatComponentText(text);
        msg.getChatStyle()
            .setColor(color);
        sender.addChatMessage(msg);
    }
}
