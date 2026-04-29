package coint.events;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import org.spongepowered.asm.mixin.Unique;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.Tags;
import coint.commands.spy.LocalSpyRegistry;
import coint.commands.spy.PersonalSpyRegistry;
import coint.config.CointConfig;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import serverutils.lib.data.Universe;

@EventBusSubscriber
public class LoginHandler {

    private static final Pattern COINTCORE_URL_PATTERN = Pattern.compile("https?://\\S+");

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        String[] lines = CointConfig.loginMessageLines;
        if (lines == null || lines.length == 0) {
            return;
        }

        String playerName = event.player.getCommandSenderName();
        for (String rawLine : lines) {
            String line = applyPlaceholders(rawLine, playerName);
            event.player.addChatMessage(buildLoginComponent(line));
        }

        if (Universe.get()
            .getPlayer(event.player)
            .isOP()) {
            LocalSpyRegistry.toggle(event.player.getCommandSenderName());
            PersonalSpyRegistry.toggle(event.player.getCommandSenderName());
        }
    }

    private static String applyPlaceholders(String rawLine, String playerName) {
        String line = rawLine == null ? "" : rawLine;
        line = line.replace("%player%", playerName);
        line = line.replace("%mod_version%", Tags.VERSION);
        return line.replace('&', (char) 167);
    }

    private static IChatComponent buildLoginComponent(String line) {
        ChatComponentText root = new ChatComponentText("");
        Matcher matcher = COINTCORE_URL_PATTERN.matcher(line);
        int cursor = 0;

        while (matcher.find()) {
            if (matcher.start() > cursor) {
                root.appendSibling(new ChatComponentText(line.substring(cursor, matcher.start())));
            }

            String matched = matcher.group();
            int end = matched.length();
            while (end > 0 && isTrailingPunctuation(matched.charAt(end - 1))) {
                end--;
            }

            String url = matched.substring(0, end);
            String trailing = matched.substring(end);

            if (!url.isEmpty()) {
                ChatComponentText link = new ChatComponentText(url);
                ChatStyle style = new ChatStyle().setUnderlined(true)
                    .setColor(EnumChatFormatting.AQUA)
                    .setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                    .setChatHoverEvent(
                        new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText("Открыть ссылку")));
                link.setChatStyle(style);
                root.appendSibling(link);
            }

            if (!trailing.isEmpty()) {
                root.appendSibling(new ChatComponentText(trailing));
            }

            cursor = matcher.end();
        }

        if (cursor < line.length()) {
            root.appendSibling(new ChatComponentText(line.substring(cursor)));
        }

        return root;
    }

    @Unique
    private static boolean isTrailingPunctuation(char c) {
        return c == '.' || c == ',' || c == '!' || c == '?' || c == ':' || c == ';' || c == ')' || c == ']';
    }
}
