package coint.mixin.minecraft;

import java.util.Arrays;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.command.WrongUsageException;
import net.minecraft.command.server.CommandMessage;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import coint.commands.CommandReply;
import coint.commands.ReplyTracker;
import coint.commands.spy.DmLogger;
import coint.commands.spy.PersonalSpyRegistry;

/**
 * Replaces the vanilla {@code /tell} / {@code /msg} / {@code /w} message format and adds
 * {@code /m} as an extra alias.
 *
 * <p>
 * New format:
 *
 * <pre>
 * §8[§eЛС§8] §bPlayerName §7→ §ftext   ← recipient sees
 * §8[§eЛС§8] §7Вы §7→ §bPlayerName§7: §ftext  ← sender sees
 * </pre>
 *
 * <p>
 * Formatting constants are defined in {@link CommandReply} so that {@code /reply} shares
 * the same visual style. Display names are taken from {@link ICommandSender#func_145748_c_()}
 * so that ServerUtilities nicknames are honoured; the name is stripped of its own colour codes
 * and rendered in a uniform {@link EnumChatFormatting#AQUA}. Message text is appended as an
 * {@link IChatComponent} sibling (never via {@code getFormattedText()}, which is
 * {@code @SideOnly(CLIENT)} and would crash on the server).
 */
@Mixin(CommandMessage.class)
public class MixinCommandMessage {

    /**
     * Adds {@code "m"} to the command's alias list so that {@code /m} works alongside the
     * built-in {@code /w} and {@code /msg}.
     * 
     * @author mawlee
     * @reason CointCore
     */
    @Overwrite
    public List<String> getCommandAliases() {
        return Arrays.asList("w", "msg", "m");
    }

    /**
     * Replaces the vanilla whisper format and routes it through the custom DM pipeline.
     * Replicates vanilla exception throwing for invalid invocations (too few args,
     * player not found, self-message) so the command dispatcher shows the correct errors.
     * 
     * @author mawlee
     * @reason CointCore
     */
    @Overwrite
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        // Too few args — let vanilla WrongUsageException propagate as normal.
        if (args.length < 2) {
            throw new WrongUsageException("commands.message.usage");
        }

        // getPlayer throws CommandException (PlayerNotFoundException) when not found.
        EntityPlayerMP target = CommandBase.getPlayer(sender, args[0]);

        // Self-message — mirror vanilla behaviour.
        if (target == sender) {
            throw new PlayerNotFoundException("commands.message.sameTarget");
        }

        IChatComponent text = CommandBase.func_147176_a(sender, args, 1, !(sender instanceof EntityPlayer));

        // getUnformattedText() is available on both sides; getFormattedText() is
        // @SideOnly(CLIENT) and must NOT be called here (server-side processCommand).
        String senderDisplay = sender.func_145748_c_()
            .getUnformattedText();
        String targetDisplay = target.func_145748_c_()
            .getUnformattedText();

        // Recipient: [ЛС] SenderName → text
        ChatComponentText toRecipient = new ChatComponentText(
            CommandReply.PREFIX + EnumChatFormatting.AQUA
                + senderDisplay
                + CommandReply.ARROW
                + EnumChatFormatting.WHITE);
        toRecipient.appendSibling(text.createCopy());
        target.addChatMessage(toRecipient);

        // Sender: [ЛС] Вы → TargetName: text
        ChatComponentText toSender = new ChatComponentText(
            CommandReply.PREFIX + EnumChatFormatting.GRAY
                + "Вы"
                + CommandReply.ARROW
                + EnumChatFormatting.BLUE
                + targetDisplay
                + EnumChatFormatting.GRAY
                + ": "
                + EnumChatFormatting.WHITE);
        toSender.appendSibling(text.createCopy());
        sender.addChatMessage(toSender);

        // Record reply targets so both parties can use /r.
        ReplyTracker.record(sender.getCommandSenderName(), target.getCommandSenderName());

        // Append to the dedicated DM log file and notify in-game spies.
        DmLogger.log("TELL", senderDisplay, targetDisplay, text.getUnformattedText());
        PersonalSpyRegistry.notifySpies(senderDisplay, targetDisplay, text);
    }
}
