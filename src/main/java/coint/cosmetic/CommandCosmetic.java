package coint.cosmetic;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

/** Console/RCON adapter for the cabinet. Deliberately unavailable as an in-game customization command. */
public final class CommandCosmetic extends CommandBase {

    public CommandCosmetic() {
        Cosmetics.register();
    }

    @Override
    public String getCommandName() {
        return "cointcosmetic";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "cointcosmetic <uuid> <prefix|prefix_color|name_color|chat_color> <base64-utf8|reset>";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return !(sender instanceof EntityPlayer);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!canCommandSenderUseCommand(sender)) throw new CommandException("Меняйте оформление в личном кабинете");
        if (args.length != 3 || args[2].length() > 128) throw new CommandException(getCommandUsage(sender));
        try {
            String value = args[2].equals("reset") ? "reset"
                : StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(
                        ByteBuffer.wrap(
                            Base64.getDecoder()
                                .decode(args[2])))
                    .toString();
            Cosmetics.set(UUID.fromString(args[0]), args[1], value);
            sender.addChatMessage(
                new ChatComponentText(
                    "Оформление сохранено. Отображается только при наличии действующего права ранга."));
        } catch (Exception e) {
            throw new CommandException(e.getMessage());
        }
    }
}
