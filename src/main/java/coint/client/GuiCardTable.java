package coint.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import coint.cards.Cards;
import coint.cards.PacketCardAction;
import coint.cards.PacketCardState;
import coint.network.WorldTravelNetwork;

/**
 * The card table window: the players sit around the table (you at the bottom, then left, top and right in turn order),
 * the draw pile and the top card in the middle, your hand below, and what happened in the corner. Played cards fly from
 * the player to the table and drawn cards from the pile to the player.
 */
public class GuiCardTable extends GuiScreen {

    static final int[] SUIT_COLORS = { 0xFFC87533, 0xFF8A9BA8, 0xFF80C8F0, 0xFFDCA0F0 };
    private static final int WILD_COLOR = 0xFF2A2A2A;
    private static final int CARD_W = 26, CARD_H = 38, BIG_W = 44, BIG_H = 64, BACK_W = 14, BACK_H = 20;
    private static final int TURN_MS = 30_000;

    private static final int B_START = 0, B_DRAW = 1, B_PASS = 2, B_LEAVE = 3, B_ADD_BOT = 4, B_REMOVE_BOT = 5,
        B_CALL = 6, B_MODE = 7, B_WATCH = 8, B_GAMES = 9, B_SUIT = 10;

    /** A card on its way across the table. */
    private static final class Fly {

        final int card;
        final boolean back, landsOnTop;
        final float x0, y0, x1, y1;
        final long start;
        final int duration;

        Fly(int card, boolean back, boolean landsOnTop, float x0, float y0, float x1, float y1, long start,
            int duration) {
            this.card = card;
            this.back = back;
            this.landsOnTop = landsOnTop;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.start = start;
            this.duration = duration;
        }
    }

    private PacketCardState state;
    /** The hand index of the wild card waiting for a suit, or -1. */
    private int pickingFor = -1;
    private final List<Fly> flies = new ArrayList<>();
    /** The top card shown while played cards are still flying to the table. */
    private int shownTop;
    private long nextFlyAt;
    /** The turn order the arrows show: it flips when a reverse card lands, not when the server says so. */
    private int shownDirection = 1;
    private int pendingReverses;
    private long flashUntil;
    /** The arrows' angle and spin (radians, radians a second), eased toward the shown direction. */
    private double arrowAngle, arrowSpeed;
    private long lastFrame;
    private String winner = "";
    private long winnerFrom, winnerUntil;
    /** Sounds and speech bubbles waiting for their card to fly, by when. */
    private final List<long[]> cueTimes = new ArrayList<>();
    private final List<Runnable> cues = new ArrayList<>();
    /** "Last card!" bubbles over players, until when. */
    private final java.util.Map<String, Long> bubbles = new java.util.HashMap<>();

    private void cue(long at, Runnable action) {
        cueTimes.add(new long[] { at });
        cues.add(action);
    }

    /** Your turn begins: a ping once the cards before it have landed. */
    void cueYourTurn() {
        cue(Math.max(System.currentTimeMillis(), nextFlyAt), () -> ClientCards.sound("note.pling", 1.2F));
    }

    private void runCues(long now) {
        for (int i = 0; i < cues.size(); i++) {
            if (now >= cueTimes.get(i)[0]) {
                Runnable action = cues.remove(i);
                cueTimes.remove(i--);
                action.run();
            }
        }
    }

    public GuiCardTable(PacketCardState state) {
        this.state = state;
        this.shownTop = state.top;
    }

    void update(PacketCardState m) {
        PacketCardState old = state;
        shownTop = displayedTop();
        state = m;
        pickingFor = -1;
        if (!m.error.isEmpty() && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText("§c" + StatCollector.translateToLocal(m.error)));
        }
        initGui();
        animate(old, m);
    }

    private boolean myTurn() {
        return state.phase == PacketCardState.PHASE_PLAYING && state.turn == state.you;
    }

    // ---- seats

    /** Where seat {@code index} is drawn: you at the bottom, then clockwise in turn order. */
    private int[] seat(int index) {
        int n = state.names.size();
        int you = Math.max(0, state.you);
        int r = Math.floorMod(index - you, Math.max(1, n));
        int place; // 0 bottom, 1 left, 2 top, 3 right
        if (r == 0) {
            place = 0;
        } else if (n == 2) {
            place = 2;
        } else if (n == 3) {
            place = r == 1 ? 1 : 3;
        } else {
            place = r;
        }
        int cy = centerY();
        return switch (place) {
            case 1 -> new int[] { 70, cy };
            case 2 -> new int[] { width / 2, 36 };
            case 3 -> new int[] { width - 70, cy };
            default -> new int[] { width / 2, handTop() + CARD_H / 2 };
        };
    }

    private int centerY() {
        return height / 2 - 24;
    }

    private int seatOf(String name) {
        return state.names.indexOf(name);
    }

    private long msLeft() {
        return Math.max(0, state.msLeft - (System.currentTimeMillis() - ClientCards.receivedAt));
    }

    // ---- animations

    private int displayedTop() {
        for (Fly fly : flies) {
            if (fly.landsOnTop) {
                return shownTop;
            }
        }
        return state.top;
    }

    /** Turns the log lines new since the last state into cards flying, one move after another. */
    private void animate(PacketCardState old, PacketCardState m) {
        int fresh = Math.min(m.logSeq - old.logSeq, m.log.size());
        if (fresh <= 0 || old.logSeq == 0 && old.log.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long at = Math.max(now, nextFlyAt);
        int cx = width / 2, cy = centerY();
        float topX = cx + 6 + BIG_W / 2F, topY = cy;
        float deckX = cx - 6 - BIG_W / 2F, deckY = cy;
        for (int i = m.log.size() - fresh; i < m.log.size(); i++) {
            PacketCardState.Line line = m.log.get(i);
            int who = seatOf(line.name);
            switch (line.kind) {
                case 3 -> { // played
                    if (who < 0) {
                        break;
                    }
                    int[] from = seat(who);
                    flies.add(new Fly(line.card, false, true, from[0], from[1], topX, topY, at, 380));
                    cue(at, () -> ClientCards.sound("dig.cloth", 1.4F));
                    if (Cards.value(line.card) == Cards.REVERSE) {
                        // The arrows turn round when the card lands.
                        pendingReverses++;
                        cue(at + 380, () -> {
                            pendingReverses--;
                            shownDirection = -shownDirection;
                            // A whip the other way, easing back to the usual spin.
                            arrowSpeed = shownDirection * 9;
                            flashUntil = System.currentTimeMillis() + 900;
                            ClientCards.sound("mob.bat.takeoff", 0.8F);
                        });
                        at += 400;
                    }
                    at += 300;
                }
                case 4, 9 -> { // drew, or missed saying "last card"
                    if (who < 0) {
                        break;
                    }
                    if (line.kind == 9) {
                        cue(at, () -> ClientCards.sound("note.bass", 0.6F));
                        at += 250;
                    }
                    int[] to = seat(who);
                    int shown = Math.min(line.card, 6);
                    for (int c = 0; c < shown; c++) {
                        long when = at + c * 90L;
                        flies.add(new Fly(-1, true, false, deckX, deckY, to[0], to[1], when, 320));
                        cue(when, () -> ClientCards.sound("random.pop", 0.8F));
                    }
                    at += 150 + shown * 90L;
                }
                case 8, 10 -> { // "last card!", said or caught someone
                    String name = line.name;
                    cue(at, () -> {
                        bubbles.put(name, System.currentTimeMillis() + 2500);
                        ClientCards.sound("note.pling", 2F);
                    });
                }
                case 7 -> { // won
                    winner = line.name;
                    winnerFrom = at;
                    winnerUntil = at + 3500;
                    cue(at, () -> ClientCards.sound("random.levelup", 1F));
                }
                default -> {}
            }
        }
        nextFlyAt = at;
    }

    // ---- buttons

    @Override
    public void initGui() {
        buttonList.clear();
        int y = height - 26;
        int cx = width / 2;
        if (pickingFor >= 0) {
            for (int s = 0; s < Cards.SUITS; s++) {
                buttonList.add(new GuiButton(B_SUIT + s, cx - 166 + s * 84, height / 2 + 30, 80, 20, suitName(s)));
            }
            buttonList.add(new GuiButton(B_SUIT + 4, cx - 40, height / 2 + 54, 80, 20, tr("cointcore.cards.cancel")));
            return;
        }
        if (state.phase == PacketCardState.PHASE_LOBBY) {
            GuiButton start = new GuiButton(B_START, cx - 105, y, 100, 20, tr("cointcore.cards.start"));
            start.enabled = state.host && state.names.size() >= 2;
            buttonList.add(start);
            if (state.host) {
                buttonList.add(
                    new GuiButton(
                        B_MODE,
                        cx - 105,
                        y - 48,
                        210,
                        20,
                        state.elimination ? "Режим: на вылет" : "Режим: обычный"));
                GuiButton add = new GuiButton(B_ADD_BOT, cx - 105, y - 24, 100, 20, tr("cointcore.cards.addBot"));
                add.enabled = state.names.size() < 4;
                buttonList.add(add);
                GuiButton remove = new GuiButton(
                    B_REMOVE_BOT,
                    cx + 5,
                    y - 24,
                    100,
                    20,
                    tr("cointcore.cards.removeBot"));
                remove.enabled = state.bots.contains(true);
                buttonList.add(remove);
            }
        } else {
            GuiButton draw = new GuiButton(B_DRAW, cx - 207, y, 100, 20, tr("cointcore.cards.draw"));
            draw.enabled = myTurn() && !state.drew;
            buttonList.add(draw);
            GuiButton pass = new GuiButton(B_PASS, cx - 102, y, 100, 20, tr("cointcore.cards.pass"));
            pass.enabled = myTurn() && state.drew;
            buttonList.add(pass);
            String label = state.canCatch ? "§6§l" + tr("cointcore.cards.catch")
                : state.called ? "§a✔ " + tr("cointcore.cards.call")
                    : state.callMsLeft > 0 ? "§c§l" + tr("cointcore.cards.call") : tr("cointcore.cards.call");
            GuiButton call = new GuiButton(B_CALL, cx + 3, y, 100, 20, label);
            call.enabled = state.callOpen;
            buttonList.add(call);
        }
        buttonList.add(
            new GuiButton(
                B_LEAVE,
                state.phase == PacketCardState.PHASE_LOBBY ? cx + 5 : cx + 108,
                y,
                100,
                20,
                tr("cointcore.cards.leave")));
        if (state.you >= 0) buttonList.add(new GuiButton(B_WATCH, width - 110, 5, 105, 20, "Наблюдать"));
        buttonList.add(new GuiButton(B_GAMES, 5, 5, 105, 20, "Список игр"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id >= B_SUIT) {
            int suit = button.id - B_SUIT;
            int index = pickingFor;
            pickingFor = -1;
            if (suit < Cards.SUITS && index >= 0) {
                send(PacketCardAction.PLAY, index, suit);
            }
            initGui();
            return;
        }
        switch (button.id) {
            case B_GAMES -> {
                mc.displayGuiScreen(null);
                mc.thePlayer.sendChatMessage("/cards");
            }
            case B_MODE -> send(PacketCardAction.MODE, 0, 0);
            case B_WATCH -> send(PacketCardAction.WATCH, 0, 0);
            case B_START -> send(PacketCardAction.START, 0, 0);
            case B_DRAW -> send(PacketCardAction.DRAW, 0, 0);
            case B_PASS -> send(PacketCardAction.PASS, 0, 0);
            case B_CALL -> send(PacketCardAction.CALL, 0, 0);
            case B_ADD_BOT -> send(PacketCardAction.ADD_BOT, 0, 0);
            case B_REMOVE_BOT -> send(PacketCardAction.REMOVE_BOT, 0, 0);
            case B_LEAVE -> {
                send(PacketCardAction.LEAVE, 0, 0);
                mc.displayGuiScreen(null);
            }
            default -> {}
        }
    }

    private static void send(int action, int index, int suit) {
        WorldTravelNetwork.CHANNEL.sendToServer(new PacketCardAction(action, index, suit));
    }

    // ---- your hand

    private int handStep() {
        int n = Math.max(1, state.hand.size());
        return Math.max(8, Math.min(CARD_W + 3, (width - 180 - CARD_W) / n));
    }

    private int handLeft() {
        int n = state.hand.size();
        return width / 2 - ((n - 1) * handStep() + CARD_W) / 2;
    }

    private int handTop() {
        return height - 34 - CARD_H - 8;
    }

    /** The hand card under the mouse (the rightmost drawn one wins where they overlap), or -1. */
    private int cardAt(int mx, int my) {
        int top = handTop();
        for (int i = state.hand.size() - 1; i >= 0; i--) {
            int left = handLeft() + i * handStep();
            int lift = liftOf(i) ? -6 : 0;
            if (mx >= left && mx < left + CARD_W && my >= top + lift && my < top + lift + CARD_H) {
                return i;
            }
        }
        return -1;
    }

    private boolean canPlay(int index) {
        if (!myTurn() || index < 0 || index >= state.hand.size()) {
            return false;
        }
        if (state.drew && index != state.hand.size() - 1) {
            return false;
        }
        return Cards.playable(state.hand.get(index), state.top, state.suit);
    }

    private boolean liftOf(int index) {
        return pickingFor < 0 && canPlay(index);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        super.mouseClicked(mx, my, button);
        if (button != 0 || pickingFor >= 0) {
            return;
        }
        int index = cardAt(mx, my);
        if (index >= 0 && canPlay(index)) {
            if (Cards.isWild(state.hand.get(index))) {
                pickingFor = index;
                initGui();
            } else {
                send(PacketCardAction.PLAY, index, -1);
            }
        }
    }

    // ---- drawing

    @Override
    public void drawScreen(int mx, int my, float partial) {
        drawDefaultBackground();
        if (state.you < 0) drawCenteredString(fontRendererObj, "Наблюдатель", width / 2, 8, 0xDDDDDD);
        long now = System.currentTimeMillis();
        runCues(now);
        drawLog();
        for (int i = 0; i < state.names.size(); i++) {
            if (i != state.you) {
                drawSeat(i, now);
            }
        }
        if (state.phase == PacketCardState.PHASE_LOBBY) {
            String wait = state.names.size() < 2 ? tr("cointcore.cards.waiting")
                : state.host ? tr("cointcore.cards.youStart") : tr("cointcore.cards.hostStarts");
            drawCenteredString(fontRendererObj, wait, width / 2, centerY(), 0xDDDDDD);
        } else {
            drawTable();
            drawHand(mx, my, now);
        }
        drawFlies(now);
        for (int i = 0; i < state.names.size(); i++) {
            Long until = bubbles.get(state.names.get(i));
            if (until != null && now < until) {
                int[] at = seat(i);
                bubble(at[0], i == state.you ? handTop() - 36 : at[1] - 38);
            }
        }
        if (now >= winnerFrom && now < winnerUntil) {
            drawBanner(now);
        }
        if (pickingFor >= 0) {
            Gui.drawRect(width / 2 - 172, height / 2 + 14, width / 2 + 172, height / 2 + 80, 0xE0101010);
            drawCenteredString(fontRendererObj, tr("cointcore.cards.pickSuit"), width / 2, height / 2 + 18, 0xFFFF55);
        }
        super.drawScreen(mx, my, partial);

        int hover = pickingFor < 0 && state.phase == PacketCardState.PHASE_PLAYING ? cardAt(mx, my) : -1;
        if (hover >= 0) {
            List<String> tip = new ArrayList<>();
            tip.add(cardName(state.hand.get(hover)));
            drawHoveringText(tip, mx, my, fontRendererObj);
        }
    }

    /** Another player's place: name, cards face down, and when it is his turn a blinking frame and his time. */
    private void drawSeat(int index, long now) {
        int[] at = seat(index);
        int sx = at[0], sy = at[1];
        boolean turn = state.phase == PacketCardState.PHASE_PLAYING && index == state.turn;
        int count = state.phase == PacketCardState.PHASE_PLAYING ? state.counts.get(index) : 0;
        if (turn) {
            int glow = 0x50 + (int) (0x50 * (0.5 + 0.5 * Math.sin(now / 160.0)));
            frame(sx - 48, sy - 26, sx + 48, sy + 22, (glow << 24) | 0xFFDD33);
            Gui.drawRect(sx - 47, sy - 25, sx + 47, sy + 21, 0x40000000);
        }
        String name = state.names.get(index);
        String color = turn ? "§e" : state.bots.get(index) ? "§b" : "§f";
        String label = (turn ? "▶ " : "") + color
            + name
            + (state.phase == PacketCardState.PHASE_PLAYING ? " §7(" + count + ")" : "");
        drawCenteredString(fontRendererObj, label, sx, sy - 22, 0xFFFFFF);
        int shown = Math.min(count, 8);
        int step = 5;
        int left = sx - ((shown - 1) * step + BACK_W) / 2;
        for (int c = 0; c < shown; c++) {
            drawBack(fontRendererObj, left + c * step, sy - 10, BACK_W, BACK_H, false);
        }
        if (turn) {
            timeBar(sx - 40, sy + 15, 80);
        }
    }

    /** A "Last card!" speech bubble centered on x. */
    private void bubble(int x, int y) {
        String text = "§l" + tr("cointcore.cards.call");
        int w = fontRendererObj.getStringWidth(text) + 10;
        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 250);
        Gui.drawRect(x - w / 2 - 1, y - 1, x + w / 2 + 1, y + 15, 0xFF000000);
        Gui.drawRect(x - w / 2, y, x + w / 2, y + 14, 0xFFFFF4C0);
        Gui.drawRect(x - 3, y + 14, x + 3, y + 17, 0xFFFFF4C0);
        fontRendererObj.drawString(text, x - fontRendererObj.getStringWidth(text) / 2, y + 3, 0xC03000);
        GL11.glPopMatrix();
    }

    private void timeBar(int x, int y, int w) {
        long left = msLeft();
        int filled = (int) (w * Math.min(1F, left / (float) TURN_MS));
        Gui.drawRect(x, y, x + w, y + 3, 0xFF202020);
        Gui.drawRect(x, y, x + filled, y + 3, left <= 10_000 ? 0xFFE04040 : 0xFFFFDD33);
    }

    private static void frame(int x0, int y0, int x1, int y1, int argb) {
        Gui.drawRect(x0, y0, x1, y0 + 1, argb);
        Gui.drawRect(x0, y1 - 1, x1, y1, argb);
        Gui.drawRect(x0, y0, x0 + 1, y1, argb);
        Gui.drawRect(x1 - 1, y0, x1, y1, argb);
    }

    private void drawLog() {
        int w = 140;
        int x = 8, y = 8;
        fontRendererObj.drawString(tr("cointcore.cards.log"), x, y, 0xAAAAAA);
        y += 11;
        for (PacketCardState.Line line : state.log) {
            for (Object part : fontRendererObj.listFormattedStringToWidth(logLine(line), w)) {
                fontRendererObj.drawString((String) part, x, y, 0xDDDDDD);
                y += 9;
            }
        }
    }

    /** Four arrows circling the piles the way the turn goes; on a reverse they flash, slow down and turn round. */
    private void drawArrows(long now) {
        if (pendingReverses == 0) {
            shownDirection = state.direction;
        }
        float dt = lastFrame == 0 ? 0 : Math.min(0.1F, (now - lastFrame) / 1000F);
        lastFrame = now;
        double target = shownDirection * 1.2;
        arrowSpeed += (target - arrowSpeed) * Math.min(1, dt * 1.8);
        arrowAngle += arrowSpeed * dt;

        int cx = width / 2, cy = centerY();
        double rx = 92, ry = 60, thick = 1.6;
        boolean flash = now < flashUntil;
        int alpha = flash ? 0xFF : 0xA0;
        int rgb = flash && now / 90 % 2 == 0 ? 0xFFFFFF : 0xFFDD33;
        // The heads point the way the turn goes; on a reverse they flip at once and the spin whips round.
        int head = shownDirection;
        double arc = Math.toRadians(52);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        net.minecraft.client.renderer.Tessellator t = net.minecraft.client.renderer.Tessellator.instance;
        for (int k = 0; k < 4; k++) {
            double start = arrowAngle + k * Math.PI / 2;
            t.startDrawing(GL11.GL_QUAD_STRIP);
            t.setColorRGBA_I(rgb, alpha);
            int steps = 14;
            for (int s = 0; s <= steps; s++) {
                double a = start + arc * s / steps;
                // The tail fades in.
                int fromTail = head > 0 ? s : steps - s;
                t.setColorRGBA_I(rgb, alpha * (fromTail + 2) / (steps + 2));
                t.addVertex(cx + Math.cos(a) * (rx - thick), cy + Math.sin(a) * (ry - thick), 0);
                t.addVertex(cx + Math.cos(a) * (rx + thick), cy + Math.sin(a) * (ry + thick), 0);
            }
            t.draw();
            double end = head > 0 ? start + arc : start;
            double tip = end + head * Math.toRadians(10);
            t.startDrawing(GL11.GL_TRIANGLES);
            t.setColorRGBA_I(rgb, alpha);
            t.addVertex(cx + Math.cos(end) * (rx - 6), cy + Math.sin(end) * (ry - 6), 0);
            t.addVertex(cx + Math.cos(tip) * rx, cy + Math.sin(tip) * ry, 0);
            t.addVertex(cx + Math.cos(end) * (rx + 6), cy + Math.sin(end) * (ry + 6), 0);
            t.draw();
        }
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private void drawTable() {
        int cx = width / 2, cy = centerY(), top = cy - BIG_H / 2;
        drawArrows(System.currentTimeMillis());
        drawBack(fontRendererObj, cx - BIG_W - 6, top, BIG_W, BIG_H, true);
        int shown = displayedTop();
        if (shown >= 0) {
            // A wild card takes the colour of the suit chosen for it once it has landed.
            int chosen = shown == state.top ? state.suit : -1;
            drawCard(fontRendererObj, shown, cx + 6, top, BIG_W, BIG_H, 2.5F, false, chosen);
        }
        int textY = top + BIG_H + 6;
        if (state.suit >= 0 && state.suit < Cards.SUITS) {
            String suit = tr("cointcore.cards.suitNow") + " " + suitName(state.suit);
            drawCenteredString(fontRendererObj, suit, cx, textY, SUIT_COLORS[state.suit] & 0xFFFFFF);
        }
    }

    private void drawHand(int mx, int my, long now) {
        int hover = pickingFor < 0 ? cardAt(mx, my) : -1;
        int top = handTop();
        if (myTurn()) {
            int n = state.hand.size();
            int x0 = handLeft() - 8, x1 = handLeft() + (n - 1) * handStep() + CARD_W + 8;
            int glow = 0x60 + (int) (0x60 * (0.5 + 0.5 * Math.sin(now / 160.0)));
            frame(x0, top - 22, x1, top + CARD_H + 6, (glow << 24) | 0xFFDD33);
            String text = "§e▶ " + tr("cointcore.cards.yourTurn") + " §7" + (msLeft() + 999) / 1000 + "s";
            if (state.drew) {
                text += "  §8" + tr("cointcore.cards.playOrPass");
            }
            drawCenteredString(fontRendererObj, text, width / 2, top - 19, 0xFFFFFF);
            timeBar(width / 2 - 40, top - 9, 80);
        }
        long callLeft = state.callMsLeft - (now - ClientCards.receivedAt);
        if (state.callMsLeft > 0 && callLeft > 0) {
            // Played down to one card without saying so: a few seconds to press the button.
            boolean blink = now / 250 % 2 == 0;
            String text = (blink ? "§c§l" : "§6§l") + StatCollector
                .translateToLocalFormatted("cointcore.cards.callNow", String.format("%.1f", callLeft / 1000F));
            drawCenteredString(fontRendererObj, text, width / 2, top - 32, 0xFFFFFF);
        }
        for (int i = 0; i < state.hand.size(); i++) {
            int left = handLeft() + i * handStep();
            boolean playable = liftOf(i);
            int y = top - (playable ? 6 : 0) - (i == hover ? 2 : 0);
            int chosen = i == pickingFor ? hoveredSuit(mx, my) : -1;
            drawCard(fontRendererObj, state.hand.get(i), left, y, CARD_W, CARD_H, 1.5F, myTurn() && !playable, chosen);
            if (playable) {
                Gui.drawRect(left, y + CARD_H, left + CARD_W, y + CARD_H + 2, 0xFFFFFF55);
            }
        }
    }

    private void drawFlies(long now) {
        for (Iterator<Fly> it = flies.iterator(); it.hasNext();) {
            Fly fly = it.next();
            if (now < fly.start) {
                continue;
            }
            float t = (now - fly.start) / (float) fly.duration;
            if (t >= 1) {
                if (fly.landsOnTop) {
                    shownTop = fly.card;
                }
                it.remove();
                continue;
            }
            float e = 1 - (1 - t) * (1 - t) * (1 - t);
            float x = fly.x0 + (fly.x1 - fly.x0) * e, y = fly.y0 + (fly.y1 - fly.y0) * e;
            if (fly.back) {
                drawBack(fontRendererObj, (int) x - BACK_W / 2, (int) y - BACK_H / 2, BACK_W, BACK_H, false);
            } else {
                int w = (int) (CARD_W + (BIG_W - CARD_W) * e), h = (int) (CARD_H + (BIG_H - CARD_H) * e);
                GL11.glPushMatrix();
                GL11.glTranslatef(0, 0, 200);
                drawCard(fly.card, (int) x - w / 2, (int) y - h / 2, w, h, e > 0.5F, false);
                GL11.glPopMatrix();
            }
        }
    }

    private void drawBanner(long now) {
        String text = StatCollector.translateToLocalFormatted("cointcore.cards.log.win", winner);
        int cy = centerY() - 10;
        Gui.drawRect(0, cy - 16, width, cy + 20, 0xC0201000);
        GL11.glPushMatrix();
        GL11.glTranslatef(width / 2F, cy, 300);
        float pulse = 2.5F + 0.15F * (float) Math.sin(now / 120.0);
        GL11.glScalef(pulse, pulse, 1);
        fontRendererObj.drawStringWithShadow(text, -fontRendererObj.getStringWidth(text) / 2, -4, 0xFFCC33);
        GL11.glPopMatrix();
    }

    static void drawBack(FontRenderer font, int x, int y, int w, int h, boolean mark) {
        Gui.drawRect(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        Gui.drawRect(x, y, x + w, y + h, 0xFF4A2E1A);
        Gui.drawRect(x + 2, y + 2, x + w - 2, y + h - 2, 0xFF6B4226);
        if (mark) {
            font.drawString("CC", x + w / 2 - font.getStringWidth("CC") / 2, y + h / 2 - 4, 0xD8B070);
        }
    }

    private void drawCard(int card, int x, int y, int w, int h, boolean big, boolean dim) {
        drawCard(fontRendererObj, card, x, y, w, h, big ? 2.5F : 1.5F, dim);
    }

    /** The suit button under the mouse while choosing a suit for a wild card, or -1. */
    private int hoveredSuit(int mx, int my) {
        for (Object o : buttonList) {
            GuiButton b = (GuiButton) o;
            int s = b.id - B_SUIT;
            if (s >= 0 && s < Cards.SUITS
                && mx >= b.xPosition
                && my >= b.yPosition
                && mx < b.xPosition + b.width
                && my < b.yPosition + b.height) {
                return s;
            }
        }
        return -1;
    }

    /** A card face; also drawn by the on-screen hand. */
    static void drawCard(FontRenderer fontRendererObj, int card, int x, int y, int w, int h, float scale, boolean dim) {
        drawCard(fontRendererObj, card, x, y, w, h, scale, dim, -1);
    }

    /** A card face; a wild card with a chosen suit (0..3) is painted in that suit's colour. */
    static void drawCard(FontRenderer fontRendererObj, int card, int x, int y, int w, int h, float scale, boolean dim,
        int chosen) {
        int suit = Cards.suit(card);
        boolean painted = Cards.isWild(card) && chosen >= 0 && chosen < Cards.SUITS;
        int color = suit < Cards.SUITS ? SUIT_COLORS[suit] : painted ? SUIT_COLORS[chosen] : WILD_COLOR;
        Gui.drawRect(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        Gui.drawRect(x, y, x + w, y + h, 0xFFF4F0E6);
        Gui.drawRect(x + 2, y + 2, x + w - 2, y + h - 2, color);
        if (Cards.isWild(card) && !painted) {
            // Four suit stripes so a wild card looks like it can be any suit.
            int stripe = (h - 8) / 4;
            for (int s = 0; s < Cards.SUITS; s++) {
                Gui.drawRect(x + 4, y + 4 + s * stripe, x + 7, y + 4 + (s + 1) * stripe, SUIT_COLORS[s]);
            }
        }
        String label = shortLabel(card);
        if (scale > 1.2F) {
            fontRendererObj.drawStringWithShadow(label, x + 3, y + 3, 0xFFFFFF);
        }
        GL11.glPushMatrix();
        GL11.glTranslatef(x + w / 2F, y + h / 2F, 0);
        GL11.glScalef(scale, scale, 1);
        int lw = fontRendererObj.getStringWidth(label);
        fontRendererObj.drawStringWithShadow(label, -lw / 2, -4, 0xFFFFFF);
        GL11.glPopMatrix();
        if (dim) {
            Gui.drawRect(x, y, x + w, y + h, 0x80000000);
        }
    }

    // ---- text

    static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    static String suitName(int suit) {
        return tr("cointcore.cards.suit." + suit);
    }

    static String shortLabel(int card) {
        int value = Cards.value(card);
        return switch (value) {
            case Cards.SKIP -> "Ø";
            case Cards.REVERSE -> "«»";
            case Cards.DRAW_TWO -> "+2";
            case Cards.WILD -> "*";
            case Cards.WILD_DRAW_FOUR -> "+4";
            default -> String.valueOf(value);
        };
    }

    static String cardName(int card) {
        int value = Cards.value(card);
        String name = value < 10 ? String.valueOf(value) : tr("cointcore.cards.value." + value);
        return Cards.isWild(card) ? name : suitName(Cards.suit(card)) + " " + name;
    }

    private static String logLine(PacketCardState.Line line) {
        String who = "§f" + line.name + "§7";
        return switch (line.kind) {
            case 0 -> StatCollector.translateToLocalFormatted("cointcore.cards.log.join", who);
            case 1 -> StatCollector.translateToLocalFormatted("cointcore.cards.log.leave", who);
            case 2 -> StatCollector.translateToLocalFormatted("cointcore.cards.log.start", who);
            case 3 -> line.suit >= 0 && line.suit < Cards.SUITS
                ? StatCollector.translateToLocalFormatted(
                    "cointcore.cards.log.playWild",
                    who,
                    cardName(line.card),
                    suitName(line.suit))
                : StatCollector.translateToLocalFormatted("cointcore.cards.log.play", who, cardName(line.card));
            case 4 -> StatCollector.translateToLocalFormatted("cointcore.cards.log.draw", who, line.card);
            case 5 -> StatCollector.translateToLocalFormatted("cointcore.cards.log.pass", who);
            case 6 -> StatCollector.translateToLocalFormatted("cointcore.cards.log.timeout", who);
            case 7 -> "§6" + StatCollector.translateToLocalFormatted("cointcore.cards.log.win", line.name);
            case 11 -> "§c" + line.name + " выбывает: " + line.card + " очк.";
            case 8 -> "§e" + StatCollector.translateToLocalFormatted("cointcore.cards.log.call", line.name);
            case 9 -> "§c"
                + StatCollector.translateToLocalFormatted("cointcore.cards.log.missed", line.name, line.card);
            case 10 -> "§6" + StatCollector.translateToLocalFormatted("cointcore.cards.log.caught", line.name);
            default -> "";
        };
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
