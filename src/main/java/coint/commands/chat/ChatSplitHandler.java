package coint.commands.chat;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.event.ServerChatEvent;

import coint.CointCore;
import coint.config.CointConfig;
import coint.integration.nilcord.NilcordBridge;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import serverutils.ServerUtilitiesPermissions;
import serverutils.lib.config.ConfigEnum;
import serverutils.lib.config.RankConfigAPI;
import serverutils.ranks.Ranks;

/**
 * Разделяет игровой чат на <b>локальный</b> и <b>глобальный</b>.
 *
 * <ul>
 * <li>Сообщение, начинающееся с {@link CointConfig#globalChatPrefix} (по умолчанию {@code !}),
 * рассылается всем онлайн-игрокам во всех измерениях.</li>
 * <li>Любое другое сообщение рассылается только тем игрокам, которые находятся
 * в пределах {@link CointConfig#localChatRadius} блоков (и, если
 * {@link CointConfig#sameDimensionOnly} включён, в том же измерении).</li>
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
public class ChatSplitHandler {

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onServerChat(ServerChatEvent event) {
        if (!CointConfig.chatSplitEnabled) {
            return;
        }

        // Если мьют-хэндлер (HIGHEST) уже отменил событие — не трогаем.
        if (event.isCanceled()) {
            return;
        }

        event.setCanceled(true);

        EntityPlayerMP sender = event.player;
        String rawMessage = event.message;

        String prefix = CointConfig.globalChatPrefix;
        boolean isGlobal = prefix != null && !prefix.isEmpty() && rawMessage.startsWith(prefix);

        String text = isGlobal ? rawMessage.substring(prefix.length())
            .trim() : rawMessage;

        if (text.isEmpty()) {
            return;
        }

        // Получаем имя с префиксом ранга из ServerUtilities (или просто ник, если SU недоступен).
        String senderName = getRankFormattedName(sender);

        if (isGlobal) {
            sendGlobal(sender, senderName, text);
        } else {
            sendLocal(sender, senderName, text);
        }
    }

    // ------------------------------------------------------------------
    // Rank name resolution
    // ------------------------------------------------------------------

    /**
     * Возвращает имя игрока, отформатированное согласно его рангу в ServerUtilities.
     *
     * <p>
     * Алгоритм:
     * <ol>
     * <li>Берём шаблон {@code CHAT_NAME_FORMAT} из ранга игрока
     * (например {@code &c[Админ]&r {name}:}).</li>
     * <li>Транслируем {@code &x} в {@code §x}.</li>
     * <li>Заменяем {@code {name}} на реальный ник.</li>
     * <li>Убираем завершающее {@code :} — оно нужно SU для собственного чата,
     * но в нашем формате разделитель уже задан в строке формата.</li>
     * </ol>
     *
     * <p>
     * При любой ошибке (Ranks не загружен, формат пустой) возвращает чистый ник.
     */
    private static String getRankFormattedName(EntityPlayerMP player) {
        String plainName = player.getGameProfile()
            .getName();

        try {
            if (Ranks.INSTANCE == null) {
                return plainName;
            }

            String format = Ranks.INSTANCE.getPlayerRank(player.getGameProfile())
                .getPermission(ServerUtilitiesPermissions.CHAT_NAME_FORMAT);

            if (format.isEmpty()) {
                return plainName;
            }

            // ranks.txt использует &x для цветов; переводим в §x
            format = format.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");

            // Подставляем ник
            format = format.replace("{name}", plainName);

            // Убираем угловые скобки <> — стандартная обёртка в шаблонах SU вида <Ранг {name}>
            format = format.replace("<", "")
                .replace(">", "");

            // Убираем хвостовое «:» (и пробелы вокруг него) — SU добавляет его
            // как разделитель чата, но в нашем формате разделитель уже есть.
            format = format.replaceAll(":\\s*$", "")
                .trim();

            return format;

        } catch (Exception e) {
            CointCore.LOG.warn("[ChatSplit] Failed to get rank format for {}: {}", plainName, e.getMessage());
            return plainName;
        }
    }

    // ------------------------------------------------------------------
    // Text colour resolution
    // ------------------------------------------------------------------

    /**
     * Возвращает Minecraft-код цвета (например {@code §c}) для текста сообщения отправителя,
     * взятый из привилегии {@code serverutilities.chat.text.color} в ranks.txt.
     *
     * <p>
     * Возвращает пустую строку, если:
     * <ul>
     * <li>Ranks недоступен</li>
     * <li>значение не задано / равно {@code white} (умолчание по умолчанию)</li>
     * </ul>
     *
     * <p>
     * Логика намеренно повторяет подход из
     * {@code ServerUtilitiesServerEventHandler.onServerChatEvent}.
     */
    private static String getTextColorCode(EntityPlayerMP player) {
        try {
            if (Ranks.INSTANCE == null) {
                return "";
            }

            EnumChatFormatting color = (EnumChatFormatting) ((ConfigEnum<?>) RankConfigAPI
                .get(player, ServerUtilitiesPermissions.CHAT_TEXT_COLOR)).getValue();

            // WHITE — значение по умолчанию; не добавляем лишний код.
            if (color == EnumChatFormatting.WHITE) {
                return "";
            }

            return color.toString(); // возвращает §x
        } catch (Exception e) {
            CointCore.LOG.warn(
                "[ChatSplit] Failed to get text color for {}: {}",
                player.getGameProfile()
                    .getName(),
                e.getMessage());
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Send helpers
    // ------------------------------------------------------------------

    private void sendGlobal(EntityPlayerMP sender, String senderName, String text) {
        String colorCode = getTextColorCode(sender);
        String formatted = String.format(CointConfig.globalChatFormat, senderName, colorCode + text);
        ChatComponentText component = new ChatComponentText(formatted);

        List<EntityPlayerMP> players = MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList;
        for (EntityPlayerMP p : players) {
            p.addChatMessage(component);
        }

        // Forward to Discord via Nilcord (no-op if Nilcord is not installed).
        NilcordBridge.forwardGlobalChat(sender, text);
        CointCore.LOG.info("[GLOBAL] {}: {}", senderName, text);
    }

    private void sendLocal(EntityPlayerMP sender, String senderName, String text) {
        String colorCode = getTextColorCode(sender);
        String formatted = String.format(CointConfig.localChatFormat, senderName, colorCode + text);
        ChatComponentText component = new ChatComponentText(formatted);

        double radiusSq = CointConfig.localChatRadius * CointConfig.localChatRadius;
        int senderDim = sender.dimension;

        List<EntityPlayerMP> players = MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList;

        int recipients = 0;
        for (EntityPlayerMP p : players) {
            if (CointConfig.sameDimensionOnly && p.dimension != senderDim) {
                continue;
            }
            if (p != sender) {
                double dx = p.posX - sender.posX;
                double dy = p.posY - sender.posY;
                double dz = p.posZ - sender.posZ;
                if (dx * dx + dy * dy + dz * dz > radiusSq) {
                    continue;
                }
            }
            p.addChatMessage(component);
            recipients++;
        }

        CointCore.LOG
            .info("[LOCAL r={}] {}: {} ({} recipients)", CointConfig.localChatRadius, senderName, text, recipients);

        // Notify admins who have /localspy enabled and were out of range.
        LocalSpyRegistry.notifySpies(sender, senderName, text);
    }
}
