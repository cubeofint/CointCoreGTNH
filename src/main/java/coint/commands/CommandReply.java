package coint.commands;

import java.util.Collections;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import coint.commands.dm.DmLogger;
import coint.commands.dm.SocialSpyRegistry;
import serverutils.ranks.ICommandWithPermission;

/**
 * Implements {@code /reply} (alias {@code /r}): sends a message to the last player who was in a
 * DM conversation with you (either they messaged you or you messaged them).
 *
 * <p>
 * Also owns the shared formatting constants ({@link #PREFIX}, {@link #ARROW}) used by both this
 * command and {@code MixinCommandMessage} so that the visual style of all DM messages
 * (including {@code /tell}, {@code /msg}, {@code /w}, {@code /m}) is defined in one place.
 */
public class CommandReply extends CommandBase {

    // ------------------------------------------------------------------
    // Shared DM formatting constants
    // ------------------------------------------------------------------

    /** DM prefix: §8[§eЛС§8] */
    public static final String PREFIX = EnumChatFormatting.DARK_GRAY + "["
        + EnumChatFormatting.YELLOW
        + "ЛС"
        + EnumChatFormatting.DARK_GRAY
        + "] ";

    /** Arrow separator between name and message text: §7→ */
    public static final String ARROW = " " + EnumChatFormatting.GRAY + "→ ";

    // ------------------------------------------------------------------
    // ICommand
    // ------------------------------------------------------------------

    @Override
    public String getCommandName() {
        return "reply";
    }

    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList("r");
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    /**
     * Delegates to the same SU permission check that {@code /tell} uses.
     *
     * <p>
     * ServerUtilities assigns each command a {@code permissionNode} via
     * {@code ICommandWithPermission} when it is registered. By fetching the live {@code /tell}
     * command object and calling {@code serverutilities$hasPermission} on it, {@code /reply}
     * automatically inherits whatever rank configuration applies to {@code /tell} — including
     * mute checks, rank restrictions, etc. If SU is not present the cast will yield
     * {@code false} and we fall back to allowing all players (level-0 behaviour).
     */
    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (!(sender instanceof EntityPlayerMP player)) return true; // console / RCON

        ICommand tellCmd = MinecraftServer.getServer()
            .getCommandManager()
            .getCommands()
            .get("tell");

        if (tellCmd instanceof ICommandWithPermission perm) {
            return perm.serverutilities$hasPermission(player);
        }

        return true; // SU not active — allow everyone (mirrors getRequiredPermissionLevel = 0)
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/reply <сообщение>";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        String targetName = ReplyTracker.getReplyTarget(sender.getCommandSenderName());
        if (targetName == null) {
            throw new CommandException("Некому отвечать.");
        }

        EntityPlayerMP target = MinecraftServer.getServer()
            .getConfigurationManager()
            .func_152612_a(targetName);
        if (target == null) {
            throw new CommandException("§e" + targetName + "§c больше не в сети.");
        }

        IChatComponent text = func_147176_a(sender, args, 0, !(sender instanceof EntityPlayer));

        // getUnformattedText() is server-safe; getFormattedText() is @SideOnly(CLIENT).
        String senderDisplay = sender.func_145748_c_()
            .getUnformattedText();
        String targetDisplay = target.func_145748_c_()
            .getUnformattedText();

        // Recipient: [ЛС] SenderName → text
        ChatComponentText toRecipient = new ChatComponentText(
            PREFIX + EnumChatFormatting.AQUA + senderDisplay + ARROW + EnumChatFormatting.WHITE);
        toRecipient.appendSibling(text.createCopy());
        target.addChatMessage(toRecipient);

        // Sender confirmation: [ЛС] Вы → TargetName: text
        ChatComponentText toSender = new ChatComponentText(
            PREFIX + EnumChatFormatting.GRAY
                + "Вы"
                + ARROW
                + EnumChatFormatting.AQUA
                + targetDisplay
                + EnumChatFormatting.GRAY
                + ": "
                + EnumChatFormatting.WHITE);
        toSender.appendSibling(text.createCopy());
        sender.addChatMessage(toSender);

        // Keep reply chain current for both parties.
        ReplyTracker.record(sender.getCommandSenderName(), targetName);

        // Append to the dedicated DM log file and notify in-game spies.
        DmLogger.log("REPLY", senderDisplay, targetDisplay, text.getUnformattedText());
        SocialSpyRegistry.notifySpies(senderDisplay, targetDisplay, text);
    }
}
