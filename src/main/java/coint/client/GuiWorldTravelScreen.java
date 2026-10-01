package coint.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import coint.network.PacketOpenWorlds;
import coint.network.PacketTravelRequest;
import coint.network.WorldTravelNetwork;
import coint.worldtravel.WorldTravelViewEntry;

public final class GuiWorldTravelScreen extends GuiScreen {

    private static final int PER_PAGE = 4;
    private static final int CARD_WIDTH = 180;
    private static final int CARD_HEIGHT = 78;
    private static final int GAP = 8;
    private static final int MAX_DETAIL_LINES = 4;

    private final PacketOpenWorlds packet;
    private int page;

    GuiWorldTravelScreen(PacketOpenWorlds packet) {
        this.packet = packet;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int pages = getPageCount();
        int y = height - 30;
        if (pages > 1) {
            buttonList.add(new GuiButton(1, width / 2 - 105, y, 70, 20, "<"));
            buttonList.add(new GuiButton(2, width / 2 + 35, y, 70, 20, ">"));
        }
        buttonList.add(new GuiButton(3, width / 2 - 30, y, 60, 20, "Закрыть"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 1 && page > 0) {
            page--;
        } else if (button.id == 2 && page + 1 < getPageCount()) {
            page++;
        } else if (button.id == 3) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) {
            return;
        }

        int start = page * PER_PAGE;
        int end = Math.min(packet.entries.size(), start + PER_PAGE);
        for (int index = start; index < end; index++) {
            int local = index - start;
            int col = local % 2;
            int row = local / 2;
            int x = width / 2 - CARD_WIDTH - GAP / 2 + col * (CARD_WIDTH + GAP);
            int y = 48 + row * (CARD_HEIGHT + GAP);
            if (mouseX < x || mouseX >= x + CARD_WIDTH || mouseY < y || mouseY >= y + CARD_HEIGHT) {
                continue;
            }

            WorldTravelViewEntry entry = packet.entries.get(index);
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
        drawCenteredString(fontRendererObj, packet.title, width / 2, 18, 0xFFFFFF);

        if (packet.entries.isEmpty()) {
            drawCenteredString(
                fontRendererObj,
                "Точки путешествия ещё не настроены",
                width / 2,
                height / 2 - 10,
                0xAAAAAA);
        }

        int start = page * PER_PAGE;
        int end = Math.min(packet.entries.size(), start + PER_PAGE);
        for (int index = start; index < end; index++) {
            int local = index - start;
            int col = local % 2;
            int row = local / 2;
            int x = width / 2 - CARD_WIDTH - GAP / 2 + col * (CARD_WIDTH + GAP);
            int y = 48 + row * (CARD_HEIGHT + GAP);
            WorldTravelViewEntry entry = packet.entries.get(index);
            drawCard(entry, x, y, mouseX, mouseY);
        }

        if (getPageCount() > 1) {
            drawCenteredString(fontRendererObj, (page + 1) + " / " + getPageCount(), width / 2, height - 24, 0xBBBBBB);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawCard(WorldTravelViewEntry entry, int x, int y, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + CARD_WIDTH && mouseY >= y && mouseY < y + CARD_HEIGHT;
        int background = hover ? 0xC0444444 : 0xB0222222;
        int border = entry.accessible ? 0xFF55AA55 : 0xFFAA5555;

        drawRect(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, border);
        drawRect(x + 1, y + 1, x + CARD_WIDTH - 1, y + CARD_HEIGHT - 1, background);

        String name = fontRendererObj.trimStringToWidth(entry.name, CARD_WIDTH - 12);
        fontRendererObj.drawStringWithShadow(name, x + 6, y + 6, 0xFFFFFF);

        String status = entry.accessible ? "§aДоступно" : "§cЗакрыто";
        fontRendererObj.drawStringWithShadow(status, x + 6, y + 19, 0xFFFFFF);

        String detail = entry.accessible ? entry.description : entry.requirementText;
        if (detail == null || detail.isEmpty()) {
            detail = "DIM " + entry.dimension;
        }

        List<String> lines = new ArrayList<>(fontRendererObj.listFormattedStringToWidth(detail, CARD_WIDTH - 12));
        if (lines.size() > MAX_DETAIL_LINES) {
            while (lines.size() > MAX_DETAIL_LINES) {
                lines.remove(lines.size() - 1);
            }
            String ellipsis = "...";
            String last = lines.get(MAX_DETAIL_LINES - 1);
            last = fontRendererObj.trimStringToWidth(last, CARD_WIDTH - 12 - fontRendererObj.getStringWidth(ellipsis));
            lines.set(MAX_DETAIL_LINES - 1, last + ellipsis);
        }

        int lineY = y + 32;
        for (String line : lines) {
            fontRendererObj.drawStringWithShadow(line, x + 6, lineY, 0xBBBBBB);
            lineY += fontRendererObj.FONT_HEIGHT;
        }
    }

    private int getPageCount() {
        return Math.max(1, (packet.entries.size() + PER_PAGE - 1) / PER_PAGE);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
