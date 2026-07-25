package coint.events;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.event.ServerChatEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import coint.commands.spy.LocalSpyRegistry;
import coint.http.HubWebSocket;
import coint.http.WebSocketMessage;
import coint.util.ChatUtil;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Разделяет игровой чат на <b>локальный</b> и <b>глобальный</b>.
 *
 * <ul>
 * <li>Сообщение, начинающееся с {@link CointConfig.Chat#prefix} (по умолчанию {@code !}),
 * рассылается всем онлайн-игрокам во всех измерениях.</li>
 * <li>Любое другое сообщение рассылается только тем игрокам, которые находятся
 * в пределах {@link CointConfig.Chat#radius} блоков и в том же измерении.</li>
 * </ul>
 *
 * <p>
 * Имя отправителя форматируется по шаблону {@code CHAT_NAME_FORMAT} из ServerUtilities Ranks
 * (например {@code &c[Админ]&r {name}:}), что обеспечивает отображение ранга в обоих каналах.
 * Если Ranks недоступны — используется обычное имя игрока.
 *
 * <p>
 * Цвет текста сообщения берётся из {@code serverutilities.chat.text.color} ранга отправителя
 * (тот же параметр, что используется в стандартном чате ServerUtilities).
 * Если значение не задано или равно {@code white} — цвет не применяется.
 *
 * <p>
 * Приоритет {@code NORMAL} — мьют ({@code MuteChatHandler}, {@code HIGHEST}) проверяется раньше
 * и отменяет событие до того, как этот хэндлер начнёт обработку.
 */
@EventBusSubscriber
public class ChatSplitHandler {

    @EventBusSubscriber.Condition
    public static boolean isEnabled() {
        return CointConfig.chat.splitEnabled;
    }

    public static final Pattern URL_PATTERN = Pattern
        .compile("((https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|])", Pattern.CASE_INSENSITIVE);

    @SuppressWarnings("unused")
    public List<IChatComponent> processAndSplit(String input) {
        List<IChatComponent> lines = new ArrayList<>();
        ChatComponentText currentLine = new ChatComponentText("");
        int currentLength = 0;

        // Разрезаем на слова, сохраняя пробелы
        String[] parts = input.split("(?<=\\s)|(?=\\s)");

        for (String part : parts) {
            Matcher matcher = URL_PATTERN.matcher(part);

            if (matcher.matches()) {
                String domain = "[" + getDomainName(part) + "]";
                int partLen = domain.length();

                if (currentLength + partLen > 100) {
                    lines.add(currentLine);
                    currentLine = new ChatComponentText("");
                    currentLength = 0;
                }

                ChatComponentText linkComp = new ChatComponentText(domain);
                linkComp.setChatStyle(
                    new ChatStyle().setColor(EnumChatFormatting.AQUA)
                        .setUnderlined(true)
                        .setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, part))
                        .setChatHoverEvent(
                            new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                new ChatComponentText("Ссылка на сторонний сайт."))));

                currentLine.appendSibling(linkComp);
                currentLength += partLen;
            } else {
                if (currentLength + part.length() > 100) {
                    if (part.length() > 100) {
                        String sub = part.substring(0, 100 - currentLength);
                        currentLine.appendSibling(new ChatComponentText(sub));
                        lines.add(currentLine);
                        currentLine = new ChatComponentText(part.substring(100 - currentLength));
                        currentLength = currentLine.getUnformattedText()
                            .length();
                    } else {
                        lines.add(currentLine);
                        currentLine = new ChatComponentText(part);
                        currentLength = part.length();
                    }
                } else {
                    currentLine.appendSibling(new ChatComponentText(part));
                    currentLength += part.length();
                }
            }
        }

        if (currentLength > 0) {
            lines.add(currentLine);
        }

        return lines;
    }

    private String getDomainName(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            String domain = uri.getHost();
            if (domain == null) return url;
            return domain.startsWith("www.") ? domain.substring(4) : domain;
        } catch (Exception e) {
            return url;
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onServerChat(ServerChatEvent event) {
        // Если мьют-хэндлер (HIGHEST) уже отменил событие — не трогаем.
        if (event.isCanceled()) {
            return;
        }
        event.setCanceled(true);

        if (event.message.contains("betterquesting.msg.sharequest")) {
            send(event.player, event.message, true);
            return;
        }

        String prefix = CointConfig.chat.prefix;
        boolean isGlobal = !prefix.isEmpty() && event.message.startsWith(prefix);

        String text = isGlobal ? event.message.substring(prefix.length())
            .trim() : event.message;
        if (text.isEmpty()) return;

        String colorCode = ChatUtil.getTextColorCode(event.player);
        send(event.player, text.replaceAll("(?<=^|\\s)", colorCode), isGlobal);
    }

    private static void send(EntityPlayerMP sender, String text, boolean isGlobal) {
        String senderName = ChatUtil.getRankFormattedName(sender);
        var origin = isGlobal ? "G" : "";
        ChatComponentText component = ChatUtil.getChatMessage(senderName, text, origin);

        var server = MinecraftServer.getServer();
        if (isGlobal) {
            server.getConfigurationManager()
                .sendChatMsg(component);

            HubWebSocket.get()
                .send(WebSocketMessage.ChatMessage.create(sender, senderName, text));
        } else {
            double radiusSq = CointConfig.chat.radius * CointConfig.chat.radius;
            int senderDim = sender.dimension;

            int recipients = 0;
            for (EntityPlayerMP p : server.getConfigurationManager().playerEntityList) {
                if (p.dimension != senderDim || sender.getDistanceSqToEntity(p) > radiusSq) {
                    continue;
                }
                p.addChatMessage(component);
                recipients++;
            }

            CointCore.LOG
                .info("[LOCAL r={}] {}: {} ({} recipients)", CointConfig.chat.radius, senderName, text, recipients);

            // Notify admins who have /localspy enabled and were out of range.
            LocalSpyRegistry.notifySpies(sender, senderName, text);
        }
    }
}
