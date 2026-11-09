package coint.client;

import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import coint.cluster.PacketServers;
import coint.network.WorldTravelNetwork;

/**
 * The servers of the network as cards, like the travel screen: a click on a running server moves the player there
 * ({@code /server <id>}). The list refreshes every few seconds while open.
 */
public final class GuiServers extends GuiScreen {

    private static final int CARD_HEIGHT = 70;
    private static final int GAP = 8;
    private static final int CARD_WIDTH = 220;
    private static final int CARD_TOP = 38;
    private static final int REFRESH_TICKS = 100;

    /** Null until the first answer. */
    private PacketServers state;
    private int ticks;

    void update(PacketServers state) {
        this.state = state;
        if (travel != null) {
            travel.visible = state.travel;
        }
    }

    private GuiButton travel;

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        buttonList.clear();
        int navY = height - 28;
        travel = new GuiButton(1, width / 2 - 112, navY, 100, 20, "Путешествия");
        travel.visible = state == null || state.travel;
        buttonList.add(travel);
        buttonList.add(new GuiButton(2, width / 2 + 12, navY, 100, 20, "Закрыть"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 1) {
            mc.displayGuiScreen(null);
            WorldTravelNetwork.requestOpen();
        } else if (button.id == 2) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public void updateScreen() {
        if (++ticks % REFRESH_TICKS == 0) {
            ClientServers.ask();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0 || state == null) {
            return;
        }
        int index = cardAt(mouseX, mouseY);
        if (index < 0) {
            return;
        }
        PacketServers.Entry entry = state.entries.get(index);
        if (entry.online && !entry.here) {
            mc.thePlayer.sendChatMessage("/server " + entry.id);
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "Серверы", width / 2, 16, 0xFFFFFF);
        if (state == null) {
            drawCenteredString(fontRendererObj, "Загрузка...", width / 2, height / 2 - 10, 0xAAAAAA);
        } else if (!state.available) {
            drawCenteredString(fontRendererObj, "§cСеть серверов сейчас недоступна", width / 2, height / 2 - 10, 0);
        } else if (state.entries.isEmpty()) {
            drawCenteredString(fontRendererObj, "Серверов пока нет", width / 2, height / 2 - 10, 0xAAAAAA);
        } else {
            int hovered = cardAt(mouseX, mouseY);
            for (int i = 0; i < visible(); i++) {
                int[] at = cardPos(i);
                drawCard(state.entries.get(i), at[0], at[1], i == hovered);
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawCard(PacketServers.Entry entry, int x, int y, boolean hover) {
        boolean canGo = entry.online && !entry.here;
        int border = entry.here ? 0xFF55AAFF : entry.online ? 0xFF55AA55 : 0xFFAA5555;
        int background = hover && canGo ? 0xC0444444 : 0xB0222222;
        drawRect(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, border);
        drawRect(x + 1, y + 1, x + CARD_WIDTH - 1, y + CARD_HEIGHT - 1, background);

        String id = "§7" + entry.id;
        int idWidth = fontRendererObj.getStringWidth(id);
        String name = fontRendererObj.trimStringToWidth(entry.name, CARD_WIDTH - 18 - idWidth);
        fontRendererObj.drawStringWithShadow(name, x + 6, y + 6, 0xFFFFFF);
        fontRendererObj.drawStringWithShadow(id, x + CARD_WIDTH - 6 - idWidth, y + 6, 0xFFFFFF);

        String status = entry.online
            ? "§aВключён §7· §f" + entry.players
                + " §7онлайн · TPS "
                + tpsColor(entry.tps)
                + String.format(Locale.ROOT, "%.1f", Math.min(20, entry.tps))
            : "§cВыключен";
        fontRendererObj.drawStringWithShadow(status, x + 6, y + 22, 0xFFFFFF);

        String marks = (entry.here ? "§bВы здесь  " : "") + (entry.home ? "§e⌂ Ваш дом" : "");
        if (!marks.isEmpty()) {
            fontRendererObj.drawStringWithShadow(marks.trim(), x + 6, y + 36, 0xFFFFFF);
        }
        if (canGo) {
            fontRendererObj.drawStringWithShadow(
                hover ? "§fНажмите, чтобы перейти" : "§7Нажмите, чтобы перейти",
                x + 6,
                y + CARD_HEIGHT - 14,
                0xFFFFFF);
        }
    }

    private static String tpsColor(double tps) {
        return tps >= 18 ? "§a" : tps >= 14 ? "§e" : "§c";
    }

    private int columns() {
        return Math.max(1, Math.min(3, (width - 32 + GAP) / (CARD_WIDTH + GAP)));
    }

    private int visible() {
        int rows = Math.max(1, (height - CARD_TOP - 40 + GAP) / (CARD_HEIGHT + GAP));
        return Math.min(state.entries.size(), rows * columns());
    }

    private int[] cardPos(int index) {
        int columns = Math.min(columns(), Math.max(1, state.entries.size()));
        int gridWidth = columns * CARD_WIDTH + (columns - 1) * GAP;
        int gridX = (width - gridWidth) / 2;
        return new int[] { gridX + (index % columns) * (CARD_WIDTH + GAP),
            CARD_TOP + (index / columns) * (CARD_HEIGHT + GAP) };
    }

    private int cardAt(int mouseX, int mouseY) {
        if (state == null || !state.available) {
            return -1;
        }
        for (int i = 0; i < visible(); i++) {
            int[] at = cardPos(i);
            if (mouseX >= at[0] && mouseX < at[0] + CARD_WIDTH && mouseY >= at[1] && mouseY < at[1] + CARD_HEIGHT) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
