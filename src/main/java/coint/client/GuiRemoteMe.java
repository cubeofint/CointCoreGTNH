package coint.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import coint.cluster.ContainerRemoteMe;
import coint.cluster.RemoteMe;
import coint.network.PacketRemoteMeAction;
import coint.network.WorldTravelNetwork;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The remote ME window, on a large chest background: the network's items in a scrolled grid with a search field, the
 * player's inventory below. Click takes a stack (right click: one) into the inventory; an item on the cursor is put
 * into the network (right click: one); shift-click on the inventory puts the whole stack.
 */
@SideOnly(Side.CLIENT)
public class GuiRemoteMe extends GuiContainer {

    private static final ResourceLocation CHEST = new ResourceLocation("textures/gui/container/generic_54.png");
    private static final int COLS = 9, ROWS = 6, GRID_X = 8, GRID_Y = 18;
    private static final int BAR_X = 178, BAR_W = 6;

    private GuiTextField search;
    private List<ClientRemoteMe.Entry> shown = new ArrayList<>();
    private int shownRevision = -1;
    private String shownSearch = "";
    private int scroll;
    /** The entry whose craft order is being typed, or null. */
    private ClientRemoteMe.Entry ordering;
    private GuiTextField amountField;
    private static final int DIALOG_X = 14, DIALOG_Y = 36, DIALOG_W = 148, DIALOG_H = 62;

    public GuiRemoteMe(InventoryPlayer inventory) {
        super(new ContainerRemoteMe(inventory, null));
        ClientRemoteMe.reset();
        xSize = 176;
        ySize = 114 + ROWS * 18;
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        search = new GuiTextField(fontRendererObj, guiLeft + 98, guiTop + 4, 72, 10);
        search.setMaxStringLength(40);
        search.setEnableBackgroundDrawing(true);
        amountField = new GuiTextField(
            fontRendererObj,
            guiLeft + DIALOG_X + 8,
            guiTop + DIALOG_Y + 24,
            DIALOG_W - 16,
            12);
        amountField.setMaxStringLength(7);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        search.updateCursorCounter();
        amountField.updateCursorCounter();
        refilter();
    }

    private void refilter() {
        String text = search.getText()
            .trim()
            .toLowerCase(Locale.ROOT);
        if (shownRevision == ClientRemoteMe.revision && text.equals(shownSearch)) {
            return;
        }
        shownRevision = ClientRemoteMe.revision;
        shownSearch = text;
        List<ClientRemoteMe.Entry> result = new ArrayList<>();
        for (ClientRemoteMe.Entry e : ClientRemoteMe.entries) {
            if (text.isEmpty() || e.search()
                .contains(text)) {
                result.add(e);
            }
        }
        shown = result;
        scroll = Math.min(scroll, maxScroll());
    }

    private int maxScroll() {
        return Math.max(0, (shown.size() + COLS - 1) / COLS - ROWS);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GL11.glColor4f(1, 1, 1, 1);
        mc.getTextureManager()
            .bindTexture(CHEST);
        int x = guiLeft, y = guiTop;
        drawTexturedModalRect(x, y, 0, 0, xSize, ROWS * 18 + 17);
        drawTexturedModalRect(x, y + ROWS * 18 + 17, 0, 126, xSize, 96);
        // The scroll bar sits outside the chest, at its right.
        int barTop = y + GRID_Y, barHeight = ROWS * 18;
        drawRect(x + BAR_X - 1, barTop - 1, x + BAR_X + BAR_W + 1, barTop + barHeight + 1, 0xFF373737);
        drawRect(x + BAR_X, barTop, x + BAR_X + BAR_W, barTop + barHeight, 0xFF8B8B8B);
        int max = maxScroll();
        int thumb = Math.max(10, barHeight / (max + 1));
        int thumbY = max == 0 ? 0 : (barHeight - thumb) * scroll / max;
        drawRect(x + BAR_X, barTop + thumbY, x + BAR_X + BAR_W, barTop + thumbY + thumb, 0xFFE0E0E0);
        search.drawTextBox();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        String title = ClientRemoteMe.title.isEmpty() ? "Удалённая ME" : "ME: " + ClientRemoteMe.title;
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(title, 88), 8, 6, 0x404040);
        fontRendererObj.drawString("Инвентарь", 8, ySize - 94, 0x404040);
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        int first = scroll * COLS;
        for (int i = 0; i < COLS * ROWS && first + i < shown.size(); i++) {
            ItemStack stack = shown.get(first + i).stack;
            int sx = GRID_X + i % COLS * 18, sy = GRID_Y + i / COLS * 18;
            try {
                itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stack, sx, sy);
            } catch (RuntimeException e) {
                // An item whose renderer fails stays blank rather than breaking the window.
            }
        }
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        for (int i = 0; i < COLS * ROWS && first + i < shown.size(); i++) {
            ClientRemoteMe.Entry entry = shown.get(first + i);
            String text = entry.count == 0 && entry.craftable ? "§bкрафт" : compact(entry.count);
            int sx = GRID_X + i % COLS * 18, sy = GRID_Y + i / COLS * 18;
            GL11.glPushMatrix();
            GL11.glTranslatef(sx + 17, sy + 17, 0);
            GL11.glScalef(0.5F, 0.5F, 1);
            fontRendererObj.drawStringWithShadow(text, -fontRendererObj.getStringWidth(text), -8, 0xFFFFFF);
            GL11.glPopMatrix();
        }
        int hovered = cellUnder(mouseX - guiLeft, mouseY - guiTop);
        if (hovered >= 0) {
            int sx = GRID_X + hovered % COLS * 18, sy = GRID_Y + hovered / COLS * 18;
            drawRect(sx, sy, sx + 16, sy + 16, 0x80FFFFFF);
        }
        String status = ClientRemoteMe.statusLine();
        if (!status.isEmpty()) {
            drawRect(GRID_X, GRID_Y + 44, GRID_X + COLS * 18 - 2, GRID_Y + 64, 0xC0000000);
            List<String> lines = fontRendererObj.listFormattedStringToWidth(status, COLS * 18 - 8);
            for (int i = 0; i < lines.size() && i < 2; i++) {
                String line = lines.get(i);
                fontRendererObj.drawStringWithShadow(
                    line,
                    GRID_X + (COLS * 18 - 2 - fontRendererObj.getStringWidth(line)) / 2,
                    GRID_Y + 46 + i * 9,
                    0xFFFFFF);
            }
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (ordering != null) {
            drawOrderDialog(mouseX, mouseY);
            return;
        }
        int cell = cellUnder(mouseX - guiLeft, mouseY - guiTop);
        if (cell >= 0 && mc.thePlayer.inventory.getItemStack() == null) {
            ClientRemoteMe.Entry entry = shown.get(scroll * COLS + cell);
            List<String> lines = new ArrayList<>();
            try {
                for (Object line : entry.stack.getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips)) {
                    lines.add(String.valueOf(line));
                }
            } catch (RuntimeException e) {
                lines.add(entry.stack.getUnlocalizedName());
            }
            lines.add("§7В сети: §f" + String.format(Locale.ROOT, "%,d", entry.count));
            if (entry.count > 0) {
                lines.add("§eЛКМ§7 — стак, §eПКМ§7 — 1 штука");
            }
            if (entry.craftable) {
                lines.add(entry.count > 0 ? "§eСКМ§7 или §eCtrl+ЛКМ§7 — заказать крафт" : "§eЛКМ§7 — заказать крафт");
            }
            drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
        } else if (inGrid(mouseX - guiLeft, mouseY - guiTop) && mc.thePlayer.inventory.getItemStack() != null) {
            List<String> lines = new ArrayList<>();
            lines.add("§eЛКМ§7 — положить в сеть, §eПКМ§7 — одну штуку");
            drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
        }
    }

    /** The grid cell under a point relative to the window that holds an item, or -1. */
    private int cellUnder(int x, int y) {
        if (!inGrid(x, y)) {
            return -1;
        }
        int cell = (y - GRID_Y) / 18 * COLS + (x - GRID_X) / 18;
        return scroll * COLS + cell < shown.size() ? cell : -1;
    }

    private static boolean inGrid(int x, int y) {
        return x >= GRID_X && x < GRID_X + COLS * 18 && y >= GRID_Y && y < GRID_Y + ROWS * 18;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (ordering != null) {
            orderDialogClicked(mouseX - guiLeft, mouseY - guiTop, mouseX, mouseY, button);
            return;
        }
        search.mouseClicked(mouseX, mouseY, button);
        if (button == 1 && mouseX >= search.xPosition
            && mouseX < search.xPosition + search.width
            && mouseY >= search.yPosition
            && mouseY < search.yPosition + search.height) {
            search.setText("");
            return;
        }
        int x = mouseX - guiLeft, y = mouseY - guiTop;
        if (inGrid(x, y) && button >= 0 && button <= 2) {
            ItemStack held = mc.thePlayer.inventory.getItemStack();
            int window = inventorySlots.windowId;
            if (held != null) {
                WorldTravelNetwork.CHANNEL.sendToServer(
                    new PacketRemoteMeAction(
                        window,
                        button == 0 ? RemoteMe.ACTION_INSERT_ALL : RemoteMe.ACTION_INSERT_ONE,
                        null,
                        0));
            } else {
                int cell = cellUnder(x, y);
                ClientRemoteMe.Entry entry = cell >= 0 ? shown.get(scroll * COLS + cell) : null;
                if (entry != null && entry.craftable
                    && (button == 2 || button == 0 && (entry.count == 0 || isCtrlKeyDown()))) {
                    openOrder(entry);
                } else if (entry != null && button != 2 && entry.count > 0) {
                    ItemStack kind = entry.stack;
                    int amount = button == 0 ? kind.getMaxStackSize() : 1;
                    WorldTravelNetwork.CHANNEL
                        .sendToServer(new PacketRemoteMeAction(window, RemoteMe.ACTION_EXTRACT, kind, amount));
                }
            }
            return;
        }
        if (x >= BAR_X && x < BAR_X + BAR_W && y >= GRID_Y && y < GRID_Y + ROWS * 18) {
            scroll = Math.round((float) (y - GRID_Y) / (ROWS * 18) * maxScroll());
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + (wheel > 0 ? -1 : 1)));
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (ordering != null) {
            if (key == Keyboard.KEY_ESCAPE) {
                ordering = null;
            } else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                sendOrder();
            } else if (Character.isDigit(c) || key == Keyboard.KEY_BACK
                || key == Keyboard.KEY_DELETE
                || key == Keyboard.KEY_LEFT
                || key == Keyboard.KEY_RIGHT) {
                    amountField.textboxKeyTyped(c, key);
                }
            return;
        }
        if (search.isFocused()) {
            if (key == Keyboard.KEY_ESCAPE) {
                search.setFocused(false);
                return;
            }
            if (search.textboxKeyTyped(c, key)) {
                scroll = 0;
                refilter();
                return;
            }
        }
        super.keyTyped(c, key);
    }

    private void openOrder(ClientRemoteMe.Entry entry) {
        ordering = entry;
        amountField.setText("1");
        amountField.setFocused(true);
        amountField.setCursorPositionEnd();
        search.setFocused(false);
    }

    private void sendOrder() {
        long amount;
        try {
            amount = Long.parseLong(
                amountField.getText()
                    .trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (amount <= 0) {
            return;
        }
        ItemStack kind = ordering.stack;
        ordering = null;
        GuiRemoteCraftConfirm.open(this, inventorySlots.windowId, kind, Math.min(amount, RemoteMe.MAX_CRAFT));
    }

    /** The two buttons of the order dialog, relative to the window: order, cancel. */
    private static final int[][] ORDER_BUTTONS = { { DIALOG_X + 8, DIALOG_Y + 42, 64, 14 },
        { DIALOG_X + DIALOG_W - 72, DIALOG_Y + 42, 64, 14 } };

    private void drawOrderDialog(int mouseX, int mouseY) {
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 300);
        int x = guiLeft + DIALOG_X, y = guiTop + DIALOG_Y;
        drawRect(x - 1, y - 1, x + DIALOG_W + 1, y + DIALOG_H + 1, 0xFF000000);
        drawRect(x, y, x + DIALOG_W, y + DIALOG_H, 0xFFC6C6C6);
        fontRendererObj.drawString("Заказать крафт:", x + 8, y + 4, 0x404040);
        String name = fontRendererObj.trimStringToWidth(ordering.stack.getDisplayName(), DIALOG_W - 16);
        fontRendererObj.drawString(name, x + 8, y + 13, 0x202080);
        amountField.drawTextBox();
        String[] labels = { "Заказать", "Отмена" };
        for (int i = 0; i < 2; i++) {
            int[] b = ORDER_BUTTONS[i];
            int bx = guiLeft + b[0], by = guiTop + b[1];
            boolean over = mouseX >= bx && mouseX < bx + b[2] && mouseY >= by && mouseY < by + b[3];
            drawRect(bx, by, bx + b[2], by + b[3], over ? 0xFF9090C0 : 0xFF8B8B8B);
            fontRendererObj.drawStringWithShadow(
                labels[i],
                bx + (b[2] - fontRendererObj.getStringWidth(labels[i])) / 2,
                by + 3,
                0xFFFFFF);
        }
        GL11.glPopMatrix();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(1, 1, 1, 1);
    }

    private void orderDialogClicked(int x, int y, int mouseX, int mouseY, int button) {
        amountField.mouseClicked(mouseX, mouseY, button);
        for (int i = 0; i < 2; i++) {
            int[] b = ORDER_BUTTONS[i];
            if (x >= b[0] && x < b[0] + b[2] && y >= b[1] && y < b[1] + b[3]) {
                if (i == 0) {
                    sendOrder();
                } else {
                    ordering = null;
                }
                return;
            }
        }
        if (x < DIALOG_X || x >= DIALOG_X + DIALOG_W || y < DIALOG_Y || y >= DIALOG_Y + DIALOG_H) {
            ordering = null;
        }
    }

    private static String compact(long value) {
        if (value < 1000) {
            return String.valueOf(value);
        }
        if (value < 1_000_000) {
            return value < 10_000 ? String.format(Locale.ROOT, "%.1fk", value / 1000.0)
                .replace(".0k", "k") : value / 1000 + "k";
        }
        if (value < 1_000_000_000L) {
            return value / 1_000_000 + "M";
        }
        return value / 1_000_000_000L + "G";
    }
}
