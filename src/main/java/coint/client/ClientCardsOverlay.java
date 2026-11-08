package coint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import coint.cards.Cards;
import coint.cards.PacketCardState;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** While seated at a card table with its window closed: whose turn, the time left, the top card and your hand. */
public final class ClientCardsOverlay {

    private static final int W = 150, CARD_W = 14, CARD_H = 20;

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Text event) {
        PacketCardState state = ClientCards.state;
        Minecraft mc = Minecraft.getMinecraft();
        if (state == null || mc.thePlayer == null || mc.currentScreen instanceof GuiCardTable) {
            return;
        }
        FontRenderer font = mc.fontRenderer;
        int right = event.resolution.getScaledWidth() - 4;
        int x = right - W, y = event.resolution.getScaledHeight() / 2 - 60;

        boolean playing = state.phase == PacketCardState.PHASE_PLAYING;
        boolean mine = playing && state.turn == state.you;
        int h = playing ? 32 + CARD_H + 8 + 12 : 34;
        Gui.drawRect(x - 4, y - 4, right + 2, y + h, mine ? 0xB0403000 : 0xA0000000);

        font.drawStringWithShadow("§6" + GuiCardTable.tr("cointcore.cards.title"), x, y, 0xFFFFFF);
        y += 11;
        if (!playing) {
            font.drawStringWithShadow(
                "§7" + StatCollector.translateToLocalFormatted("cointcore.cards.hud.seated", state.names.size()),
                x,
                y,
                0xFFFFFF);
            font.drawStringWithShadow("§8" + GuiCardTable.tr("cointcore.cards.hud.open"), x, y + 10, 0xFFFFFF);
            return;
        }

        long left = Math.max(0, state.msLeft - (System.currentTimeMillis() - ClientCards.receivedAt));
        String who = mine ? "§e" + GuiCardTable.tr("cointcore.cards.yourTurn")
            : state.turn >= 0 && state.turn < state.names.size()
                ? "§f"
                    + StatCollector.translateToLocalFormatted("cointcore.cards.theirTurn", state.names.get(state.turn))
                : "";
        String time = (left <= 10_000 ? "§c" : "§7") + (left + 999) / 1000 + "s";
        font.drawStringWithShadow(who, x, y, 0xFFFFFF);
        font.drawStringWithShadow(time, right - font.getStringWidth(time), y, 0xFFFFFF);
        y += 12;

        // The top card, the suit to follow, then the hand.
        if (state.top >= 0) {
            GuiCardTable.drawCard(font, state.top, x, y, CARD_W, CARD_H, 1F, false);
        }
        if (state.suit >= 0 && state.suit < Cards.SUITS) {
            font.drawStringWithShadow(
                GuiCardTable.suitName(state.suit),
                x + CARD_W + 4,
                y + 6,
                GuiCardTable.SUIT_COLORS[state.suit] & 0xFFFFFF);
        }
        int handX = x + 60;
        int n = state.hand.size();
        int step = n <= 1 ? CARD_W : Math.min(CARD_W + 1, (right - handX - CARD_W) / (n - 1));
        for (int i = 0; i < n; i++) {
            int card = state.hand.get(i);
            boolean fits = mine && Cards.playable(card, state.top, state.suit) && (!state.drew || i == n - 1);
            GuiCardTable.drawCard(font, card, handX + i * step, y - (fits ? 3 : 0), CARD_W, CARD_H, 1F, mine && !fits);
        }
        y += CARD_H + 8;
        font.drawStringWithShadow("§8" + GuiCardTable.tr("cointcore.cards.hud.open"), x, y, 0xFFFFFF);
    }
}
