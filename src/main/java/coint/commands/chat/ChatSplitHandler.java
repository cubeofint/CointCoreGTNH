package coint.commands.chat;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.event.ServerChatEvent;

import coint.CointCore;
import coint.config.CointConfig;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

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
 * Отправителю всегда показывается его же сообщение, чтобы он видел, что оно было отправлено.
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

        String text = isGlobal ? rawMessage.substring(prefix.length()).trim() : rawMessage;

        if (text.isEmpty()) {
            return;
        }

        String senderName = sender.func_145748_c_().getUnformattedText();

        if (isGlobal) {
            sendGlobal(senderName, text);
        } else {
            sendLocal(sender, senderName, text);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void sendGlobal(String senderName, String text) {
        String formatted = String.format(CointConfig.globalChatFormat, senderName, text);
        ChatComponentText component = new ChatComponentText(formatted);

        List<EntityPlayerMP> players = MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList;
        for (EntityPlayerMP p : players) {
            p.addChatMessage(component);
        }

        CointCore.LOG.info("[GLOBAL] {}: {}", senderName, text);
    }

    private void sendLocal(EntityPlayerMP sender, String senderName, String text) {
        String formatted = String.format(CointConfig.localChatFormat, senderName, text);
        ChatComponentText component = new ChatComponentText(formatted);

        double radiusSq = CointConfig.localChatRadius * CointConfig.localChatRadius;
        int senderDim = sender.dimension;

        List<EntityPlayerMP> players = MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList;

        int recipients = 0;
        for (EntityPlayerMP p : players) {
            // Фильтр по измерению
            if (CointConfig.sameDimensionOnly && p.dimension != senderDim) {
                continue;
            }
            // Фильтр по расстоянию (сам отправитель — всегда получает)
            if (p != sender) {
                double dx = p.posX - sender.posX;
                double dy = p.posY - sender.posY;
                double dz = p.posZ - sender.posZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > radiusSq) {
                    continue;
                }
            }
            p.addChatMessage(component);
            recipients++;
        }

        CointCore.LOG
            .info("[LOCAL r={}] {}: {} ({} recipients)", CointConfig.localChatRadius, senderName, text, recipients);
    }
}


