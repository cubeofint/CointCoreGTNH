package coint.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import coint.network.PacketOpenWorlds;
import coint.network.PacketTravelRequest;
import coint.network.WorldTravelNetwork;
import coint.worldtravel.WorldTravelViewEntry;

public final class GuiWorldTravelScreen extends GuiScreen {

    private static final int CARD_HEIGHT = 118;
    private static final int GAP = 8;
    private static final int MIN_CARD_WIDTH = 190;
    private static final int MAX_CARD_WIDTH = 280;
    private static final int MAX_COLUMNS = 3;
    private static final int MAX_ROWS = 7;
    private static final int CARD_TOP = 38;
    private static final int BOTTOM_RESERVED = 70;
    private static final int FILTER_ALL_ID = 1000;
    private static final int FILTER_TIER_BASE_ID = 1100;

    private final PacketOpenWorlds packet;
    private int page;
    private int selectedTier = -1;

    GuiWorldTravelScreen(PacketOpenWorlds packet) {
        this.packet = packet;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        clampPage();

        int navY = height - 28;
        if (getPageCount() > 1) {
            buttonList.add(new GuiButton(1, width / 2 - 105, navY, 70, 20, "<"));
            buttonList.add(new GuiButton(2, width / 2 + 35, navY, 70, 20, ">"));
        }
        buttonList.add(new GuiButton(3, width / 2 - 30, navY, 60, 20, "Закрыть"));

        List<Integer> tiers = getTiers();
        int count = tiers.size() + 1;
        int spacing = 4;
        int buttonWidth = Math.max(24, Math.min(44, (width - 20 - (count - 1) * spacing) / Math.max(1, count)));
        int totalWidth = count * buttonWidth + (count - 1) * spacing;
        int x = (width - totalWidth) / 2;
        int filterY = height - 52;

        buttonList.add(new GuiButton(FILTER_ALL_ID, x, filterY, buttonWidth, 20, selectedTier < 0 ? "§eВсе" : "Все"));
        x += buttonWidth + spacing;

        for (int tier : tiers) {
            buttonList.add(
                new GuiButton(
                    FILTER_TIER_BASE_ID + tier,
                    x,
                    filterY,
                    buttonWidth,
                    20,
                    selectedTier == tier ? "§eT" + tier : "T" + tier));
            x += buttonWidth + spacing;
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 1 && page > 0) {
            page--;
        } else if (button.id == 2 && page + 1 < getPageCount()) {
            page++;
        } else if (button.id == 3) {
            mc.displayGuiScreen(null);
        } else if (button.id == FILTER_ALL_ID) {
            selectedTier = -1;
            page = 0;
            initGui();
        } else if (button.id >= FILTER_TIER_BASE_ID) {
            selectedTier = button.id - FILTER_TIER_BASE_ID;
            page = 0;
            initGui();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) {
            return;
        }

        List<WorldTravelViewEntry> entries = getFilteredEntries();
        int perPage = getPerPage();
        int start = page * perPage;
        int end = Math.min(entries.size(), start + perPage);
        int columns = getColumns();
        int cardWidth = getCardWidth(columns);
        int gridWidth = columns * cardWidth + (columns - 1) * GAP;
        int gridX = (width - gridWidth) / 2;

        for (int index = start; index < end; index++) {
            int local = index - start;
            int col = local % columns;
            int row = local / columns;
            int x = gridX + col * (cardWidth + GAP);
            int y = CARD_TOP + row * (CARD_HEIGHT + GAP);
            if (mouseX < x || mouseX >= x + cardWidth || mouseY < y || mouseY >= y + CARD_HEIGHT) {
                continue;
            }

            WorldTravelViewEntry entry = entries.get(index);
            if (entry.accessible) {
                WorldTravelNetwork.CHANNEL.sendToServer(new PacketTravelRequest(entry.id));
                mc.displayGuiScreen(null);
            }
            return;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, packet.title, width / 2, 16, 0xFFFFFF);

        List<WorldTravelViewEntry> entries = getFilteredEntries();
        if (entries.isEmpty()) {
            drawCenteredString(
                fontRendererObj,
                selectedTier < 0 ? "Точки путешествия ещё не настроены" : "В этом тире пока нет точек путешествия",
                width / 2,
                height / 2 - 10,
                0xAAAAAA);
        }

        int perPage = getPerPage();
        int start = page * perPage;
        int end = Math.min(entries.size(), start + perPage);
        int columns = getColumns();
        int cardWidth = getCardWidth(columns);
        int gridWidth = columns * cardWidth + (columns - 1) * GAP;
        int gridX = (width - gridWidth) / 2;

        for (int index = start; index < end; index++) {
            int local = index - start;
            int col = local % columns;
            int row = local / columns;
            int x = gridX + col * (cardWidth + GAP);
            int y = CARD_TOP + row * (CARD_HEIGHT + GAP);
            drawCard(entries.get(index), x, y, cardWidth, mouseX, mouseY);
        }

        if (getPageCount() > 1) {
            drawCenteredString(fontRendererObj, (page + 1) + " / " + getPageCount(), width / 2, height - 22, 0xBBBBBB);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawCard(WorldTravelViewEntry entry, int x, int y, int cardWidth, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + cardWidth && mouseY >= y && mouseY < y + CARD_HEIGHT;
        int background = hover ? 0xC0444444 : 0xB0222222;
        int border = entry.accessible ? 0xFF55AA55 : 0xFFAA5555;

        drawRect(x, y, x + cardWidth, y + CARD_HEIGHT, border);
        drawRect(x + 1, y + 1, x + cardWidth - 1, y + CARD_HEIGHT - 1, background);

        String tier = "§7T" + entry.tier;
        int tierWidth = fontRendererObj.getStringWidth(tier);
        String name = fontRendererObj.trimStringToWidth(entry.name, cardWidth - 18 - tierWidth);
        fontRendererObj.drawStringWithShadow(name, x + 6, y + 6, 0xFFFFFF);
        fontRendererObj.drawStringWithShadow(tier, x + cardWidth - 6 - tierWidth, y + 6, 0xFFFFFF);

        String status = entry.accessible ? "§aДоступно" : "§cЗакрыто";
        fontRendererObj.drawStringWithShadow(status, x + 6, y + 20, 0xFFFFFF);

        String detail = entry.accessible ? entry.description : entry.requirementText;
        if (detail == null || detail.isEmpty()) {
            detail = "DIM " + entry.dimension;
        }

        int maxDetailLines = Math.max(1, (CARD_HEIGHT - 38) / fontRendererObj.FONT_HEIGHT);
        List<String> lines = new ArrayList<>(fontRendererObj.listFormattedStringToWidth(detail, cardWidth - 12));
        if (lines.size() > maxDetailLines) {
            while (lines.size() > maxDetailLines) {
                lines.remove(lines.size() - 1);
            }
            String ellipsis = "...";
            String last = lines.get(maxDetailLines - 1);
            last = fontRendererObj.trimStringToWidth(last, cardWidth - 12 - fontRendererObj.getStringWidth(ellipsis));
            lines.set(maxDetailLines - 1, last + ellipsis);
        }

        int lineY = y + 34;
        for (String line : lines) {
            fontRendererObj.drawStringWithShadow(line, x + 6, lineY, 0xBBBBBB);
            lineY += fontRendererObj.FONT_HEIGHT;
        }
    }

    private List<WorldTravelViewEntry> getFilteredEntries() {
        if (selectedTier < 0) {
            return packet.entries;
        }
        List<WorldTravelViewEntry> result = new ArrayList<>();
        for (WorldTravelViewEntry entry : packet.entries) {
            if (entry.tier == selectedTier) {
                result.add(entry);
            }
        }
        return result;
    }

    private List<Integer> getTiers() {
        Set<Integer> tiers = new TreeSet<>();
        for (WorldTravelViewEntry entry : packet.entries) {
            tiers.add(Math.max(0, entry.tier));
        }
        return new ArrayList<>(tiers);
    }

    private int getColumns() {
        int available = Math.max(MIN_CARD_WIDTH, width - 32);
        return Math.max(1, Math.min(MAX_COLUMNS, (available + GAP) / (MIN_CARD_WIDTH + GAP)));
    }

    private int getRows() {
        int available = Math.max(CARD_HEIGHT, height - CARD_TOP - BOTTOM_RESERVED);
        return Math.max(1, Math.min(MAX_ROWS, (available + GAP) / (CARD_HEIGHT + GAP)));
    }

    private int getCardWidth(int columns) {
        int available = Math.max(MIN_CARD_WIDTH, width - 32 - (columns - 1) * GAP);
        return Math.max(MIN_CARD_WIDTH, Math.min(MAX_CARD_WIDTH, available / columns));
    }

    private int getPerPage() {
        return getColumns() * getRows();
    }

    private int getPageCount() {
        int size = getFilteredEntries().size();
        int perPage = Math.max(1, getPerPage());
        return Math.max(1, (size + perPage - 1) / perPage);
    }

    private void clampPage() {
        int pages = getPageCount();
        if (page >= pages) {
            page = pages - 1;
        }
        if (page < 0) {
            page = 0;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
