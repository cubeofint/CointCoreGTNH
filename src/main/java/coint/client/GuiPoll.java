package coint.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.resources.I18n;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.ChatComponentTranslation;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import coint.cluster.PollNetwork;
import coint.poll.PollDefinition;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

public final class GuiPoll extends GuiScreen {

    private static final long OFFER_MAX_AGE_MS = 20000;
    private static final long VOTE_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(12);
    private static final int LINE_HEIGHT = 12;

    private static Offer active;
    private final Offer offer;
    private final List<String> lines = new ArrayList<>();
    private int questionLines, visibleLines, offset, left, right, top, bottom, textY;

    private GuiPoll(Offer offer) {
        this.offer = offer;
    }

    public static void receive(NetworkManager connection, PollNetwork.Prompt prompt) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> {
            if (!connection.isChannelOpen() || prompt.timeLeft <= 0) return;
            if (!matches(connection, prompt.definition.id)) active = new Offer(connection, prompt.definition);
            active.timeLeft = prompt.timeLeft;
            active.receivedAt = System.nanoTime();
        });
    }

    public static void receive(NetworkManager connection, PollNetwork.Reply reply) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> {
            if (!matches(connection, reply.id)) return;
            if (reply.outcome == PollNetwork.Outcome.RETRY) {
                active.voting = false;
                active.retry = true;
            } else {
                active = null;
                if (reply.outcome == PollNetwork.Outcome.SAVED && onConnection(mc, connection)) {
                    mc.thePlayer.addChatMessage(new ChatComponentTranslation("cointcore.poll.saved"));
                }
            }
        });
    }

    private static boolean matches(NetworkManager connection, String id) {
        return active != null && active.connection == connection && active.definition.id.equals(id);
    }

    private static boolean onConnection(Minecraft mc, NetworkManager connection) {
        NetHandlerPlayClient handler = mc.getNetHandler();
        return handler != null && handler.getNetworkManager() == connection && connection.isChannelOpen();
    }

    public static final class Ticker {

        @SubscribeEvent
        public void tick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (active != null) {
                long age = active.age();
                if (!onConnection(mc, active.connection) || age >= OFFER_MAX_AGE_MS || age >= active.timeLeft) {
                    active = null;
                }
            }
            if (mc.currentScreen instanceof GuiPoll screen && screen.offer != active) mc.displayGuiScreen(null);
            if (active != null && mc.currentScreen == null && mc.thePlayer != null && mc.thePlayer.isEntityAlive()) {
                mc.displayGuiScreen(new GuiPoll(active));
            }
        }

        @SubscribeEvent
        public void disconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
            Minecraft.getMinecraft()
                .func_152344_a(() -> { if (active != null && active.connection == event.manager) active = null; });
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        PollDefinition definition = offer.definition;
        int panelWidth = Math.min(460, Math.max(32, width - 24));
        left = (width - panelWidth) / 2;
        right = left + panelWidth;
        int contentWidth = Math.max(12, panelWidth - 28);
        lines.clear();
        lines.addAll(fontRendererObj.listFormattedStringToWidth(definition.question, contentWidth));
        questionLines = lines.size();
        if (!definition.details.isEmpty()) {
            lines.add("");
            lines.addAll(fontRendererObj.listFormattedStringToWidth(definition.details, contentWidth));
        }

        int rows = definition.answers.size();
        int buttonStep = Math.max(12, Math.min(24, (height - 70) / rows));
        visibleLines = Math.max(1, Math.min(lines.size(), (height - 68 - rows * buttonStep) / LINE_HEIGHT));
        int panelHeight = 52 + visibleLines * LINE_HEIGHT + rows * buttonStep;
        top = Math.max(2, (height - panelHeight) / 2);
        bottom = top + panelHeight;
        textY = top + 14;
        scroll(0);

        buttonList.clear();
        int buttonY = textY + visibleLines * LINE_HEIGHT + 12;
        for (int i = 0; i < rows; i++) {
            String answer = definition.answers.get(i);
            String label = fontRendererObj.getStringWidth(answer) <= contentWidth - 12 ? answer
                : fontRendererObj.trimStringToWidth(answer, Math.max(1, contentWidth - 24)) + "…";
            GuiButton button = new GuiButton(
                i,
                left + 14,
                buttonY + i * buttonStep,
                contentWidth,
                buttonStep - 4,
                label);
            button.enabled = !offer.voting;
            buttonList.add(button);
        }
    }

    private void scroll(int direction) {
        offset = Math.max(0, Math.min(lines.size() - visibleLines, offset + direction));
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) scroll(wheel > 0 ? -3 : 3);
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE) return;
        if (key == Keyboard.KEY_UP) scroll(-1);
        else if (key == Keyboard.KEY_DOWN) scroll(1);
        else super.keyTyped(character, key);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled || offer.voting || offer != active || !onConnection(mc, offer.connection)) return;
        offer.voting = true;
        offer.sentAt = System.nanoTime();
        offer.retry = false;
        setButtonsEnabled(false);
        PollNetwork.vote(offer.definition.id, button.id);
    }

    @Override
    public void updateScreen() {
        if (offer.voting && System.nanoTime() - offer.sentAt >= VOTE_TIMEOUT_NS) {
            offer.voting = false;
            offer.retry = true;
        }
        setButtonsEnabled(!offer.voting);
    }

    private void setButtonsEnabled(boolean enabled) {
        for (Object item : buttonList) ((GuiButton) item).enabled = enabled;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(left, top, right, bottom, 0xF0141B24);
        drawRect(left, top, right, top + 2, 0xFF65A5E9);
        for (int i = 0; i < visibleLines && i + offset < lines.size(); i++) {
            int index = i + offset;
            fontRendererObj.drawStringWithShadow(
                lines.get(index),
                left + 14,
                textY + i * LINE_HEIGHT,
                index < questionLines ? 0xFFFFFF : 0xCFDAE8);
        }

        int footerY = bottom - 16;
        if (offer.voting || offer.retry || lines.size() > visibleLines) {
            String key = offer.voting ? "saving" : offer.retry ? "retry" : "scroll";
            fontRendererObj.drawString(I18n.format("cointcore.poll." + key), left + 14, footerY, 0xACBED1);
        }
        long seconds = Math.max(0, (offer.timeLeft - offer.age() + 999) / 1000);
        String leftText = I18n.format(
            "cointcore.poll.time",
            String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60));
        fontRendererObj.drawString(leftText, right - 14 - fontRendererObj.getStringWidth(leftText), footerY, 0xACBED1);
        super.drawScreen(mouseX, mouseY, partialTicks);
        for (Object item : buttonList) {
            GuiButton button = (GuiButton) item;
            String label = offer.definition.answers.get(button.id);
            if (!label.equals(button.displayString) && mouseX >= button.xPosition
                && mouseX < button.xPosition + right - left - 28
                && mouseY >= button.yPosition
                && mouseY < button.yPosition + 20) {
                drawHoveringText(
                    fontRendererObj.listFormattedStringToWidth(label, Math.max(40, right - left - 28)),
                    mouseX,
                    mouseY,
                    fontRendererObj);
            }
        }
    }

    private static final class Offer {

        final NetworkManager connection;
        final PollDefinition definition;
        long timeLeft, receivedAt, sentAt;
        boolean voting, retry;

        Offer(NetworkManager connection, PollDefinition definition) {
            this.connection = connection;
            this.definition = definition;
        }

        long age() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - receivedAt);
        }
    }
}
