package coint.commands;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import coint.CointCore;
import coint.cluster.ClusterService;
import coint.commands.spy.DmLogger;
import coint.commands.spy.PersonalSpyRegistry;
import coint.integration.discord.DiscordModerationWebhook;

/**
 * Private messages to players on the other servers of the network: /msg and /r send them through the cluster when the
 * player is not here but online elsewhere, and the server he is on shows them to him.
 */
public final class DirectMessages {

    private DirectMessages() {}

    /**
     * Sends {@code text} to {@code targetName} on another server. False when he is not online on another server (or
     * the cluster is off): then he is nowhere to be found.
     */
    public static boolean sendRemote(ICommandSender sender, String targetName, IChatComponent text)
        throws CommandException {
        String target = ClusterService.remotePlayerName(targetName);
        if (target == null) {
            return false;
        }
        String senderName = sender.getCommandSenderName();
        if (target.equalsIgnoreCase(senderName)) {
            return false;
        }
        if (MessageTracker.checkIgnore(senderName, target)) {
            throw new CommandException("Вам запрещено писать этому игроку.");
        }
        String senderDisplay = sender.func_145748_c_()
            .getUnformattedText();
        String plain = text.getUnformattedText();
        if (!ClusterService.publishDirect(senderName, senderDisplay, target, plain)) {
            return false;
        }
        String server = ClusterService.remotePlayerServer(target);

        // Sender: [ЛС] Вы → Target (Server): text
        ChatComponentText toSender = new ChatComponentText(
            CommandReply.PREFIX + EnumChatFormatting.GRAY
                + "Вы"
                + CommandReply.ARROW
                + EnumChatFormatting.BLUE
                + target
                + serverTag(server)
                + EnumChatFormatting.GRAY
                + ": "
                + EnumChatFormatting.WHITE);
        toSender.appendSibling(text.createCopy());
        sender.addChatMessage(toSender);

        MessageTracker.setReply(senderName, target);
        DmLogger.log("TELL", senderDisplay, target + "@" + server, plain);
        DiscordModerationWebhook.logDm(senderDisplay, target + " (" + server + ")", plain);
        PersonalSpyRegistry.notifySpies(senderDisplay, target, text);
        return true;
    }

    /** A private message from another server: shown to the player it is for, when he is here. */
    public static void deliverRemote(String senderName, String senderDisplay, String server, String targetName,
        String text) {
        MinecraftServer mc = MinecraftServer.getServer();
        if (mc == null || targetName == null) {
            return;
        }
        EntityPlayerMP target = mc.getConfigurationManager()
            .func_152612_a(targetName);
        if (target == null) {
            CointCore.LOG
                .info("[Cluster] Private message from {} to {} not delivered: not here", senderName, targetName);
            return;
        }
        // His own ignore list lives on the server he is on.
        if (MessageTracker.checkIgnore(senderName, target.getCommandSenderName())) {
            return;
        }
        ChatComponentText message = new ChatComponentText(
            CommandReply.PREFIX + EnumChatFormatting.AQUA
                + senderDisplay
                + serverTag(server)
                + CommandReply.ARROW
                + EnumChatFormatting.WHITE
                + text);
        target.addChatMessage(message);
        MessageTracker.setReply(target.getCommandSenderName(), senderName);
        DmLogger.log("TELL", senderDisplay + "@" + server, target.getCommandSenderName(), text);
        PersonalSpyRegistry.notifySpies(senderDisplay, target.getCommandSenderName(), new ChatComponentText(text));
    }

    private static String serverTag(String server) {
        return server == null || server.isEmpty() ? "" : " " + EnumChatFormatting.DARK_GRAY + "(" + server + ")";
    }
}
