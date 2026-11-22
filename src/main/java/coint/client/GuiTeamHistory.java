package coint.client;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Mouse;

import coint.cluster.PacketTeamHistory;
import coint.cluster.TeamHistoryNetwork;
import coint.teamhistory.HistoryEvent;

/** Read-only timeline of the authenticated player's team, shared by all three nodes. */
public final class GuiTeamHistory extends GuiScreen {

    private static int sequence;
    private static final int ROW_HEIGHT = 56;
    private final Deque<Long> newer = new ArrayDeque<>();
    private PacketTeamHistory state;
    private long before, waitingSince, nextRequest;
    private int expected, offset;
    private boolean loading;

    private GuiTeamHistory(PacketTeamHistory packet) {
        state = packet;
    }

    public static void receive(PacketTeamHistory packet) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> {
            if (mc.thePlayer == null) return;
            if (packet.open) {
                mc.displayGuiScreen(new GuiTeamHistory(packet));
            } else if (mc.currentScreen instanceof GuiTeamHistory gui && packet.request == gui.expected) {
                gui.state = packet;
                gui.offset = 0;
                gui.loading = false;
            }
        });
    }

    private void request() {
        loading = true;
        waitingSince = System.currentTimeMillis();
        nextRequest = waitingSince + 600;
        expected = ++sequence;
        TeamHistoryNetwork.CHANNEL.sendToServer(new PacketTeamHistory.Request(before, expected));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        buttonList.clear();
        int buttonWidth = Math.min(100, (width - 36) / 4);
        int x = (width - (buttonWidth * 4 + 12)) / 2;
        for (int i = 0; i < 4; i++) buttonList.add(
            new GuiButton(
                i,
                x + i * (buttonWidth + 4),
                height - 28,
                buttonWidth,
                20,
                tr(new String[] { "newer", "older", "refresh", "back" }[i])));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 3) {
            ClientServers.open();
            return;
        }
        if (loading || System.currentTimeMillis() < nextRequest) return;
        if (button.id == 0 && !newer.isEmpty()) {
            before = newer.pop();
        } else if (button.id == 1 && state.older && !state.entries.isEmpty()) {
            newer.push(before);
            before = state.entries.get(state.entries.size() - 1).row;
        } else if (button.id == 2) {
            newer.clear();
            before = 0;
        } else return;
        request();
    }

    @Override
    public void updateScreen() {
        if (loading && System.currentTimeMillis() - waitingSince > 20000) {
            loading = false;
            state = new PacketTeamHistory();
            state.error = "cointcore.history.unavailable";
        }
        for (Object object : buttonList) {
            GuiButton button = (GuiButton) object;
            boolean ready = !loading && System.currentTimeMillis() >= nextRequest;
            button.enabled = switch (button.id) {
                case 0 -> ready && !newer.isEmpty();
                case 1 -> ready && state.error.isEmpty() && state.older;
                case 2 -> ready;
                default -> true;
            };
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (!loading && wheel != 0) offset = Math
            .max(0, Math.min(Math.max(0, state.entries.size() - visible()), offset + (wheel < 0 ? 1 : -1)));
    }

    private int visible() {
        return Math.max(1, (height - 132) / ROW_HEIGHT);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int panelWidth = Math.min(620, width - 28);
        int left = (width - panelWidth) / 2;
        drawRect(left, 28, left + panelWidth, height - 38, 0xC0121C27);
        drawCenteredString(fontRendererObj, tr("title"), width / 2, 12, 0xFFFFFF);
        drawCenteredString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(state.title, panelWidth - 24),
            width / 2,
            38,
            0xF1CD70);
        String since = state.since > 0 ? tr("since", date(state.since)) : "";
        drawCenteredString(fontRendererObj, since, width / 2, 54, 0x9EAAB8);
        if (loading || !state.error.isEmpty() || state.entries.isEmpty()) {
            String message = loading ? tr("loading") : !state.error.isEmpty() ? I18n.format(state.error) : tr("empty");
            int y = 82;
            for (Object line : fontRendererObj.listFormattedStringToWidth(message, panelWidth - 32)) {
                drawCenteredString(fontRendererObj, line.toString(), width / 2, y, 0xBBC5D0);
                y += 12;
            }
        } else {
            offset = Math.min(offset, Math.max(0, state.entries.size() - visible()));
            List<String> tooltip = null;
            int count = Math.min(visible(), state.entries.size() - offset);
            drawRect(left + 14, 82, left + 16, 82 + count * ROW_HEIGHT - 8, 0xFF45586B);
            for (int i = 0; i < count; i++) {
                HistoryEvent event = state.entries.get(offset + i);
                int top = 78 + i * ROW_HEIGHT;
                boolean hover = mouseX >= left + 24 && mouseX < left + panelWidth - 10
                    && mouseY >= top
                    && mouseY < top + ROW_HEIGHT - 5;
                drawRect(left + 24, top, left + panelWidth - 10, top + ROW_HEIGHT - 5, hover ? 0xFF293B4E : 0xFF1C2A39);
                drawRect(
                    left + 11,
                    top + 12,
                    left + 19,
                    top + 20,
                    event.kind.equals("epoch") ? 0xFFF1CD70 : 0xFF69CCE0);
                String title = event.kind.equals("epoch") ? tr("epoch", event.label)
                    : tr("move", node(event.node), node(event.target));
                fontRendererObj.drawStringWithShadow(date(event.time), left + 32, top + 5, 0x91A6BC);
                fontRendererObj.drawStringWithShadow(
                    fontRendererObj.trimStringToWidth(title, panelWidth - 54),
                    left + 32,
                    top + 20,
                    0xFFFFFF);
                String details = event.actor + " · " + node(event.kind.equals("move") ? event.target : event.node);
                fontRendererObj.drawStringWithShadow(
                    fontRendererObj.trimStringToWidth(details, panelWidth - 54),
                    left + 32,
                    top + 35,
                    0xB3C4D4);
                if (hover) {
                    tooltip = new ArrayList<>();
                    for (Object line : fontRendererObj.listFormattedStringToWidth(title, Math.min(360, width - 36)))
                        tooltip.add(line.toString());
                    tooltip.add(date(event.time));
                    tooltip.add(tr("actor", event.actor));
                    tooltip.add(tr("node", node(event.node)));
                }
            }
            drawCenteredString(
                fontRendererObj,
                tr("position", offset + 1, offset + count, state.entries.size()),
                width / 2,
                height - 52,
                0x91A6BC);
            super.drawScreen(mouseX, mouseY, partialTicks);
            if (tooltip != null) drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
            return;
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private static String node(String id) {
        return switch (id) {
            case "alpha" -> "Alpha";
            case "endgame" -> "Endgame";
            case "hub" -> "Hub";
            default -> id;
        };
    }

    private static String date(long time) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(time));
    }

    private static String tr(String key, Object... args) {
        return I18n.format("cointcore.history." + key, args);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
