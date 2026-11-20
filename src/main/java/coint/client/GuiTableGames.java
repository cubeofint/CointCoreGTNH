package coint.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import coint.cards.PacketTableAction;
import coint.cards.PacketTableState;
import coint.cards.PlayingCards;
import coint.network.WorldTravelNetwork;

/** The free-table picker and ordinary-deck games. Card faces use public server encodings only. */
public class GuiTableGames extends GuiScreen {

    private static final int LEAVE = 0, DEAL = 1, DRAW = 2, UNDO = 3, FOLD = 4, CALL = 5, RAISE = 6, ADD_BOT = 7,
        REMOVE_BOT = 8, HIT = 9, STAND = 10, SELECT = 20;
    private static final int[] GAME_CHOICES = { 0, PacketTableState.SOLITAIRE, PacketTableState.POKER,
        PacketTableState.BLACKJACK };
    private PacketTableState state;
    private GuiTextField raise;
    private int selected = -1, selectedCount;
    private long received;
    private SolitaireBoard board;
    private SolitaireBoard.Drag drag;
    private SolitaireWinAnimation victory;

    public GuiTableGames(PacketTableState state) {
        this.state = state;
        received = System.currentTimeMillis();
    }

    void update(PacketTableState message) {
        if (message.session != state.session || message.game != state.game
            || message.dim != state.dim
            || message.x != state.x
            || message.y != state.y
            || message.z != state.z) return;
        if (message.revision != state.revision || !message.owner || message.won) {
            selected = -1;
            drag = null;
        } else
            if (drag != null && drag.pending && !message.error.isEmpty()) drag.returnToPile(System.currentTimeMillis());
        if (message.turn != state.turn || message.phase != state.phase) raise = null;
        state = message;
        received = System.currentTimeMillis();
        initGui();
    }

    private String tr(String name) {
        return StatCollector.translateToLocal("cointcore.table." + name);
    }

    private void button(int id, int x, int y, int w, String label, boolean enabled) {
        GuiButton button = new GuiButton(id, x, y, w, 20, label);
        button.enabled = enabled;
        buttonList.add(button);
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int bottom = height - 26, cx = width / 2;
        if (state.game == PacketTableState.MENU) {
            for (int i = 0; i < GAME_CHOICES.length; i++)
                button(SELECT + i, cx - 105, height / 2 - 60 + i * 30, 210, tr("game." + GAME_CHOICES[i]), true);
            button(LEAVE, cx - 50, bottom, 100, tr("close"), true);
        } else if (state.game == PacketTableState.SOLITAIRE) {
            if (board != null && (board.width != width || board.height != height)) {
                drag = null;
                selected = -1;
            }
            board = state.piles.size() == 13 ? new SolitaireBoard(state.piles, width, height) : null;
            if (!state.won || board == null) victory = null;
            else {
                long now = System.nanoTime() / 1000000L;
                victory = victory == null ? new SolitaireWinAnimation(board, now) : victory.resized(board, now);
            }
            button(DEAL, 8, bottom, 102, tr("newDeal"), state.owner);
            button(UNDO, 115, bottom, 90, tr("undo"), state.owner && state.canUndo);
            button(LEAVE, width - 108, bottom, 100, tr("leave"), true);
        } else if (state.game == PacketTableState.BLACKJACK) {
            boolean playing = state.phase == 1;
            int w = Math.min(100, (width - 24) / 3), left = (width - 3 * w - 8) / 2;
            if (playing) {
                button(HIT, left, bottom, w, tr("blackjack.hit"), state.owner);
                button(STAND, left + w + 4, bottom, w, tr("blackjack.stand"), state.owner);
            } else {
                boolean canDeal = state.chips.size() >= 1 && state.wagers.size() >= 1
                    && state.chips.get(0) >= state.wagers.get(0);
                button(DEAL, left, bottom, w * 2 + 4, tr("deal"), state.owner && canDeal);
            }
            button(LEAVE, left + 2 * (w + 4), bottom, w, tr("leave"), true);
        } else {
            boolean playing = state.phase >= 1 && state.phase <= 4;
            boolean turn = playing && state.you >= 0 && state.turn == state.you;
            if (!playing) {
                button(DEAL, cx - 51, bottom - 24, 102, tr("deal"), state.owner && state.names.size() >= 2);
                button(ADD_BOT, cx - 157, bottom, 102, tr("addBot"), state.owner && state.names.size() < 4);
                button(REMOVE_BOT, cx - 51, bottom, 102, tr("removeBot"), state.owner && state.bots.contains(true));
                button(LEAVE, cx + 55, bottom, 102, tr("leave"), true);
            } else {
                int w = Math.min(92, (width - 24) / 4);
                int left = (width - w * 4 - 12) / 2;
                button(FOLD, left, bottom, w, tr("fold"), turn);
                button(
                    CALL,
                    left + w + 4,
                    bottom,
                    w,
                    state.call == 0 ? tr("check") : tr("call") + " " + state.call,
                    turn);
                button(RAISE, left + 2 * (w + 4), bottom, w, tr("raise"), turn && state.canRaise);
                button(LEAVE, left + 3 * (w + 4), bottom, w, tr("leave"), true);
                String old = raise == null ? "" : raise.getText();
                boolean focused = raise != null && raise.isFocused();
                raise = new GuiTextField(fontRendererObj, left + 2 * (w + 4), bottom - 23, w, 18);
                raise.setMaxStringLength(6);
                raise.setText(old.isEmpty() ? String.valueOf(Math.min(state.minRaise, state.maxRaise)) : old);
                raise.setEnabled(turn && state.canRaise);
                raise.setFocused(focused);
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (drag != null && drag.pending && button.id != LEAVE) return;
        drag = null;
        if (button.id >= SELECT && button.id < SELECT + GAME_CHOICES.length) {
            send(PacketTableAction.SELECT, GAME_CHOICES[button.id - SELECT], 0, 0);
            return;
        }
        switch (button.id) {
            case LEAVE -> {
                if (state.game != PacketTableState.MENU) send(PacketTableAction.LEAVE, 0, 0, 0);
                mc.displayGuiScreen(null);
            }
            case DEAL -> {
                victory = null;
                send(PacketTableAction.DEAL, 0, 0, 0);
            }
            case UNDO -> {
                victory = null;
                send(PacketTableAction.UNDO, 0, 0, 0);
            }
            case DRAW -> send(PacketTableAction.DRAW, 0, 0, 0);
            case FOLD -> send(PacketTableAction.FOLD, 0, 0, 0);
            case CALL -> send(PacketTableAction.CALL, 0, 0, 0);
            case ADD_BOT -> send(PacketTableAction.ADD_BOT, 0, 0, 0);
            case REMOVE_BOT -> send(PacketTableAction.REMOVE_BOT, 0, 0, 0);
            case HIT -> send(PacketTableAction.HIT, 0, 0, 0);
            case STAND -> send(PacketTableAction.STAND, 0, 0, 0);
            case RAISE -> {
                try {
                    send(PacketTableAction.RAISE, Integer.parseInt(raise.getText()), 0, 0);
                } catch (NumberFormatException ignored) {}
            }
            default -> {}
        }
    }

    protected void send(int action, int a, int b, int c) {
        WorldTravelNetwork.CHANNEL.sendToServer(new PacketTableAction(state, action, a, b, c));
    }

    @Override
    public void drawScreen(int mx, int my, float partial) {
        drawDefaultBackground();
        drawRect(4, 28, width - 4, height - 32, 0xDB153B32);
        drawCenteredString(
            fontRendererObj,
            tr(state.game == PacketTableState.MENU ? "choose" : "game." + state.game),
            width / 2,
            10,
            0xFFFFFF);
        if (state.game == PacketTableState.SOLITAIRE) {
            long now = System.currentTimeMillis();
            if (drag != null) {
                drag.move(mx, my);
                if (drag.pending && now - drag.pendingAt > 5000) drag.returnToPile(now);
                if (drag.returningAt >= 0 && now - drag.returningAt >= SolitaireBoard.Drag.RETURN_MS) drag = null;
            }
            for (Object value : buttonList) {
                GuiButton button = (GuiButton) value;
                if (button.id == DEAL || button.id == UNDO)
                    button.enabled = state.owner && (drag == null || !drag.pending)
                        && (button.id != UNDO || state.canUndo);
            }
            solitaire(mx, my);
        } else if (state.game == PacketTableState.POKER) poker();
        else if (state.game == PacketTableState.BLACKJACK) blackjack();
        super.drawScreen(mx, my, partial);
        if (state.game == PacketTableState.POKER && state.phase >= 1 && state.phase <= 4 && raise != null)
            raise.drawTextBox();
        drawDraggedCards();
        for (Object value : buttonList) {
            GuiButton button = (GuiButton) value;
            if (mx < button.xPosition || mx >= button.xPosition + button.width
                || my < button.yPosition
                || my >= button.yPosition + button.height) continue;
            String tip = switch (button.id) {
                case SELECT -> tr("tip.uno");
                case SELECT + 1 -> tr("tip.solitaire");
                case SELECT + 2 -> tr("tip.poker");
                case SELECT + 3 -> tr("tip.blackjack");
                case HIT, STAND -> tr("blackjack.rules");
                case DEAL -> state.game == PacketTableState.BLACKJACK ? tr("blackjack.rules") : "";
                case RAISE -> tr("tip.raise");
                default -> "";
            };
            if (!tip.isEmpty()) drawHoveringText(
                fontRendererObj.listFormattedStringToWidth(tip, Math.min(230, Math.max(100, width / 2 - 15))),
                mx,
                my,
                fontRendererObj);
        }
    }

    private void card(int value, int x, int y, int w, int h, boolean selected) {
        drawRect(x - 1, y - 1, x + w + 1, y + h + 1, selected ? 0xFFFFD35A : 0xFF162125);
        drawRect(x, y, x + w, y + h, value < 0 ? 0xFF284D86 : 0xFFF1EEE3);
        if (value < 0) {
            drawRect(x + 3, y + 3, x + w - 3, y + h - 3, 0xFF3E68A7);
            return;
        }
        int color = PlayingCards.red(value) ? 0xB82531 : 0x202630;
        String rank = (w >= 26 ? "§l" : "") + PlayingCards.label(value);
        int icon = Math.max(3, Math.round(w * 0.2F));
        float scale = Math.max(
            1,
            Math.min(1.55F, (w - (w >= 23 ? icon + 6F : 4F)) / Math.max(1, fontRendererObj.getStringWidth(rank))));
        GL11.glPushMatrix();
        GL11.glTranslatef(x + 2, y + 2, 0);
        GL11.glScalef(scale, scale, 1);
        fontRendererObj.drawString(rank, 0, 0, color);
        GL11.glPopMatrix();
        if (w >= 23) CardSuitRenderer.draw(PlayingCards.suit(value), x + w - icon - 2, y + 3, icon, icon, color);
        int bodyTop = 4 + (int) Math.ceil(9 * scale), bodyHeight = h - bodyTop - 3;
        int symbol = Math.min(Math.round(w * 0.58F), bodyHeight);
        CardSuitRenderer.draw(
            PlayingCards.suit(value),
            x + (w - symbol) / 2F,
            y + bodyTop + (bodyHeight - symbol) / 2F,
            symbol,
            symbol,
            color);
    }

    private void empty(int x, int y, int w, int h, String label) {
        drawRect(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF6E9D8C);
        drawRect(x, y, x + w, y + h, 0xFF194637);
        drawCenteredString(fontRendererObj, label, x + w / 2, y + h / 2 - 4, 0xAAC7BB);
    }

    private String emptyLabel(int pile) {
        return pile == 0 ? "↻" : pile == 1 ? "" : pile < 6 ? "A" : "K";
    }

    private void solitaire(int mx, int my) {
        if (board == null) return;
        if (victory != null) victory.advance(System.nanoTime() / 1000000L);
        if (drag != null && drag.moved && drag.first == 0)
            empty(drag.originX, drag.originY, board.w, board.h, emptyLabel(drag.pile));
        else if (drag != null && drag.moved && drag.pile < 6)
            card(state.piles.get(drag.pile)[drag.first - 1], drag.originX, drag.originY, board.w, board.h, false);
        for (SolitaireBoard.Card place : board.cards) {
            if (drag != null && drag.hides(place.pile, place.index)) continue;
            int value = victory != null && place.pile >= 2 && place.pile < 6 ? victory.remainingTop(place.pile - 2)
                : place.value;
            if (value == -2) empty(place.x, place.y, place.w, place.h, emptyLabel(place.pile));
            else card(
                value,
                place.x,
                place.y,
                place.w,
                place.h,
                selected == place.pile && place.index >= state.piles.get(place.pile).length - selectedCount);
            if (place.pile == 0) drawCenteredString(
                fontRendererObj,
                String.valueOf(state.piles.get(0).length),
                place.x + place.w / 2,
                place.y + place.h + 3,
                0xD7EEE2);
        }
        if (victory != null) {
            for (SolitaireWinAnimation.Stamp stamp : victory.trail) victoryCard(stamp);
            for (SolitaireWinAnimation.Stamp stamp : victory.flying) victoryCard(stamp);
        }
        if (drag != null && drag.moved && !drag.pending && drag.returningAt < 0) {
            int to = board.targetAt(mx, my);
            if (to >= 0) {
                SolitaireBoard.Card target = board.targets[to];
                int color = board.canMove(drag.pile, drag.first, to) ? 0xFF71E6A0 : 0xFFE68474;
                int y = target.y + (to >= 6 && state.piles.get(to).length > 0 ? board.step : 0);
                frame(target.x - 3, y - 3, target.w + 6, target.h + 6, color);
            }
        }
        String status = state.won ? tr("solitaire.win")
            : !state.owner ? tr("spectator")
                : drag != null && drag.pending ? tr("solitaire.waiting") : tr("solitaire.help");
        drawCenteredString(fontRendererObj, status, width / 2, 30, state.won ? 0xFFD35A : 0xD6E8DB);
        drawString(fontRendererObj, tr("moves") + ": " + state.moves, 8, height - 39, 0xFFFFFF);
    }

    private void victoryCard(SolitaireWinAnimation.Stamp stamp) {
        card(stamp.card, (int) Math.round(stamp.x), (int) Math.round(stamp.y), victory.w, victory.h, false);
    }

    private void frame(int x, int y, int w, int h, int color) {
        drawRect(x, y, x + w, y + 2, color);
        drawRect(x, y + h - 2, x + w, y + h, color);
        drawRect(x, y, x + 2, y + h, color);
        drawRect(x + w - 2, y, x + w, y + h, color);
    }

    private void drawDraggedCards() {
        if (drag == null || !drag.moved || board == null) return;
        long now = System.currentTimeMillis();
        int x = drag.renderX(now), y = drag.renderY(now), spread = drag.spread(board.step, now);
        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 250);
        for (int i = 0; i < drag.cards.length; i++) {
            drawRect(x + 3, y + i * spread + 4, x + board.w + 3, y + i * spread + board.h + 4, 0x55000000);
            card(drag.cards[i], x, y + i * spread, board.w, board.h, true);
        }
        GL11.glPopMatrix();
    }

    private int[] seatPosition(int index) {
        int relative = Math.floorMod(index - Math.max(0, state.you), state.names.size());
        if (relative == 0) return new int[] { width / 2, height - 110 };
        if (state.names.size() == 2 || relative == 2) return new int[] { width / 2, height < 300 ? 38 : 43 };
        return relative == 1 ? new int[] { 64, height / 2 - 23 } : new int[] { width - 64, height / 2 - 23 };
    }

    private void poker() {
        drawCenteredString(fontRendererObj, tr("playChips"), width / 2, height - 38, 0xA2BBAA);
        boolean compact = height < 300;
        int cy = height / 2 - 32, w = Math.min(compact ? 20 : 32, (width - 160) / 5), h = w * 3 / 2;
        drawString(fontRendererObj, tr("pot") + ": " + state.pot, 8, 31, 0xFFE19A);
        for (int i = 0; i < 5; i++) {
            int x = width / 2 - (5 * (w + 4) - 4) / 2 + i * (w + 4);
            if (i < state.board.length) card(state.board[i], x, cy, w, h, false);
            else empty(x, cy, w, h, "");
        }
        if (!state.result.isEmpty()) drawCenteredString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(state.result, width - 24),
            width / 2,
            27,
            0xFFD35A);
        else drawCenteredString(fontRendererObj, tr("street." + state.phase), width / 2, 27, 0xCADDD1);
        for (int i = 0; i < state.names.size(); i++) {
            int[] seat = seatPosition(i);
            int x = seat[0], y = seat[1];
            boolean turn = state.turn == i;
            String name = (i == state.dealer ? "[D] " : "") + state.names.get(i);
            if (turn) name += " (" + Math.max(0, state.msLeft - (System.currentTimeMillis() - received)) / 1000 + "s)";
            drawCenteredString(
                fontRendererObj,
                fontRendererObj.trimStringToWidth(name, 120),
                x,
                y,
                turn ? 0xFFE19A : 0xFFFFFF);
            drawCenteredString(
                fontRendererObj,
                String.valueOf(state.chips.get(i)) + " / " + state.wagers.get(i),
                x,
                y + 10,
                0xB3D4C0);
            int[] cards = state.hands.get(i);
            int cw = compact ? 18 : 23, ch = compact ? 26 : 34;
            for (int j = 0; j < 2; j++)
                card(j < cards.length ? cards[j] : -1, x - cw - 1 + j * (cw + 2), y + 20, cw, ch, turn);
            if (state.folded.get(i)) drawCenteredString(fontRendererObj, tr("folded"), x, y + 30, 0xFF7E7E);
        }
        if (state.you < 0) drawString(fontRendererObj, tr("spectator"), width - 100, 31, 0xFFFFFF);
    }

    private void blackjack() {
        if (state.hands.size() != 2 || state.chips.isEmpty() || state.wagers.isEmpty()) return;
        boolean playing = state.phase == 1;
        String status = !state.result.isEmpty() ? StatCollector.translateToLocal(state.result)
            : !state.owner ? tr("spectator") : playing ? tr("blackjack.turn") : tr("blackjack.ready");
        if (!playing && state.chips.get(0) < state.wagers.get(0)) status = tr("blackjack.noChips");
        drawCenteredString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(status, width - 20),
            width / 2,
            30,
            0xFFE19A);
        drawCenteredString(
            fontRendererObj,
            tr("blackjack.chips") + ": "
                + state.chips.get(0)
                + "    "
                + tr("blackjack.bet")
                + ": "
                + state.wagers.get(0),
            width / 2,
            44,
            0xCADDD1);
        int w = Math.min(54, Math.max(24, (height - 137) / 3)), h = w * 3 / 2;
        blackjackHand(state.hands.get(1), tr("blackjack.dealer"), 74, w, h, playing);
        blackjackHand(state.hands.get(0), state.names.get(0), Math.max(142, height / 2 + 16), w, h, false);
        drawCenteredString(fontRendererObj, tr("blackjack.playChips"), width / 2, height - 39, 0xA2BBAA);
    }

    private void blackjackHand(int[] cards, String name, int y, int w, int h, boolean hidden) {
        String label = name
            + (cards.length == 0 ? "" : ": " + PlayingCards.blackjackTotal(cards) + (hidden ? " + ?" : ""));
        drawCenteredString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(label, width - 24),
            width / 2,
            y - 12,
            0xFFFFFF);
        if (cards.length == 0) {
            empty(width / 2 - w / 2, y, w, h, "");
            return;
        }
        int step = cards.length == 1 ? w : Math.min(w + 6, (width - 32 - w) / (cards.length - 1));
        int left = (width - w - (cards.length - 1) * step) / 2;
        for (int i = 0; i < cards.length; i++) card(cards[i], left + i * step, y, w, h, false);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        super.mouseClicked(mx, my, button);
        if (state.game == PacketTableState.POKER && raise != null) raise.mouseClicked(mx, my, button);
        if (board == null || !state.owner || state.won || drag != null && drag.pending) return;
        drag = null;
        SolitaireBoard.Card hit = board.at(mx, my);
        if (hit != null && hit.pile == 0 && button == 0) {
            selected = -1;
            send(PacketTableAction.DRAW, 0, 0, 0);
            return;
        }
        if (button == 0 && selected >= 0) {
            int to = board.targetAt(mx, my);
            int first = state.piles.get(selected).length - selectedCount;
            if (board.canMove(selected, first, to)) {
                int from = selected;
                selected = -1;
                send(PacketTableAction.MOVE, from, to, selectedCount);
                return;
            }
        }
        if (!board.liftable(hit)) {
            selected = -1;
            return;
        }
        int[] pile = state.piles.get(hit.pile);
        if (button == 1) {
            for (int to = 2; to < 6; to++) if (board.canMove(hit.pile, pile.length - 1, to)) {
                selected = -1;
                send(PacketTableAction.MOVE, hit.pile, to, 1);
                return;
            }
        } else if (button == 0) {
            boolean deselect = selected == hit.pile && selectedCount == pile.length - hit.index;
            selected = hit.pile;
            selectedCount = pile.length - hit.index;
            drag = new SolitaireBoard.Drag(board, hit, mx, my, state.revision, state.session, deselect);
        }
    }

    @Override
    protected void mouseClickMove(int mx, int my, int button, long held) {
        super.mouseClickMove(mx, my, button, held);
        if (button == 0 && drag != null) drag.move(mx, my);
    }

    @Override
    protected void mouseMovedOrUp(int mx, int my, int button) {
        super.mouseMovedOrUp(mx, my, button);
        if (button != 0 || drag == null || drag.pending || drag.returningAt >= 0) return;
        if (board == null || drag.session != state.session
            || drag.revision != state.revision
            || !state.owner
            || state.won) {
            drag = null;
            return;
        }
        drag.move(mx, my);
        if (!drag.moved) {
            if (drag.deselect) selected = -1;
            drag = null;
            return;
        }
        selected = -1;
        int to = board.targetAt(mx, my);
        long now = System.currentTimeMillis();
        if (board.canMove(drag.pile, drag.first, to)) {
            drag.pending = true;
            drag.pendingAt = now;
            send(PacketTableAction.MOVE, drag.pile, to, drag.count);
        } else drag.returnToPile(now);
    }

    @Override
    public void onGuiClosed() {
        drag = null;
        victory = null;
        selected = -1;
        super.onGuiClosed();
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (state.game == PacketTableState.POKER && raise != null && raise.textboxKeyTyped(character, key)) return;
        super.keyTyped(character, key);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
