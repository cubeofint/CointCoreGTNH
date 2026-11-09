package coint.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import coint.network.WorldTravelNetwork;
import coint.trade.PacketTradeAction;
import coint.trade.PacketTradeState;

/**
 * The trade window: what I give (left) and what the partner gives (right), my inventory below with the trade chat
 * beside it. Nothing moves while trading: a click puts part of an inventory stack on the table, and the server takes
 * it out only when both have pressed "Обменять". Closing the window calls the trade off.
 */
public class GuiTrade extends GuiScreen {

    private static final int W = 360, H = 232;
    private static final int MINE_X = 8, THEIRS_X = 190, GRID_Y = 30;
    private static final int COLS = 9, ROWS = 4, SLOT = 18;
    private static final int INV_Y = 150, HOTBAR_Y = 208;
    private static final int CHAT_X = 190, CHAT_Y = 150, CHAT_W = 162, CHAT_BOTTOM = 202;

    private static final int PANEL = 0xF01E2228, PANEL_EDGE = 0xFF0A0C0E, SECTION = 0xFF2A2F37,
        SECTION_READY = 0xFF1F4A26, EDGE_READY = 0xFF4CD964, SLOT_BG = 0xFF14171B, SLOT_EDGE = 0xFF3C434D;

    private PacketTradeState state;
    /** The server closed the window (trade over): don't tell it the window was closed. */
    boolean closedByServer;
    private GuiTextField chat;
    private GuiButton ready, confirm;
    private int left, top;

    /** The amount dialog (middle click on an item): the inventory slot it is for, or -1 when closed. */
    private int amountSlot = -1;
    private GuiTextField amount;
    private GuiButton amountOk, amountCancel;
    private static final int DLG_W = 160, DLG_H = 64;

    public GuiTrade(PacketTradeState state) {
        this.state = state;
    }

    void update(PacketTradeState state) {
        this.state = state;
        refreshButtons();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        left = (width - W) / 2;
        top = (height - H) / 2;
        buttonList.clear();
        ready = new GuiButton(0, left + MINE_X, top + 106, 78, 20, "");
        confirm = new GuiButton(1, left + MINE_X + 84, top + 106, 78, 20, "");
        buttonList.add(ready);
        buttonList.add(confirm);
        String typed = chat == null ? "" : chat.getText();
        chat = new GuiTextField(fontRendererObj, left + CHAT_X + 1, top + CHAT_BOTTOM + 6, CHAT_W - 2, 12);
        chat.setMaxStringLength(120);
        chat.setText(typed);
        int dx = left + (W - DLG_W) / 2, dy = top + 40;
        String typedAmount = amount == null ? "" : amount.getText();
        amount = new GuiTextField(fontRendererObj, dx + 8, dy + 20, DLG_W - 16, 12);
        amount.setMaxStringLength(6);
        amount.setText(typedAmount);
        amount.setFocused(true);
        amountOk = new GuiButton(2, dx + 8, dy + 38, 70, 20, "§aОК");
        amountCancel = new GuiButton(3, dx + DLG_W - 78, dy + 38, 70, 20, "Отмена");
        refreshButtons();
    }

    // ---------------------------------------------------------------- the amount dialog

    /** Opens the dialog asking how many of the slot's items to put on the table. */
    private void openAmount(int slot) {
        ItemStack stack = mc.thePlayer.inventory.mainInventory[slot];
        if (stack == null || state.myReady || blockedEpoch(slot) != null) {
            return;
        }
        amountSlot = slot;
        int now = reserved(slot);
        amount.setText(String.valueOf(now > 0 ? now : stack.stackSize));
        amount.setFocused(true);
        amount.setCursorPositionEnd();
        amount.setSelectionPos(0);
    }

    /** Sets what lies on the table from this slot to the typed amount (0 takes it all back). */
    private void applyAmount() {
        int slot = amountSlot;
        amountSlot = -1;
        ItemStack stack = mc.thePlayer.inventory.mainInventory[slot];
        int wanted;
        try {
            wanted = Integer.parseInt(
                amount.getText()
                    .trim());
        } catch (NumberFormatException e) {
            return;
        }
        int max = stack == null ? 0 : stack.stackSize;
        wanted = Math.max(0, Math.min(wanted, max));
        int now = reserved(slot);
        if (wanted > now) {
            send(PacketTradeAction.ADD, slot, wanted - now, "");
            return;
        }
        int offer = offerOf(slot);
        if (offer >= 0 && wanted < now) {
            send(PacketTradeAction.REMOVE, offer, wanted == 0 ? 0 : now - wanted, "");
        }
    }

    private int offerOf(int slot) {
        for (int i = 0; i < state.mySlots.length; i++) {
            if (state.mySlots[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private void drawAmount(int mouseX, int mouseY) {
        ItemStack stack = mc.thePlayer.inventory.mainInventory[amountSlot];
        if (stack == null || state.myReady) {
            amountSlot = -1;
            return;
        }
        int dx = left + (W - DLG_W) / 2, dy = top + 40;
        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 400);
        drawRect(left, top, left + W, top + H, 0x90000000);
        drawRect(dx - 1, dy - 1, dx + DLG_W + 1, dy + DLG_H + 1, EDGE_READY);
        drawRect(dx, dy, dx + DLG_W, dy + DLG_H, PANEL_EDGE);
        String max = "из " + stack.stackSize;
        int maxW = fontRendererObj.getStringWidth(max);
        String title = fontRendererObj.trimStringToWidth(stack.getDisplayName(), DLG_W - 20 - maxW);
        fontRendererObj.drawString(title, dx + 8, dy + 6, 0xFFFFFF);
        fontRendererObj.drawString(max, dx + DLG_W - 8 - maxW, dy + 6, 0x808080);
        amount.drawTextBox();
        amountOk.drawButton(mc, mouseX, mouseY);
        amountCancel.drawButton(mc, mouseX, mouseY);
        GL11.glPopMatrix();
    }

    private void refreshButtons() {
        if (ready == null) {
            return;
        }
        ready.displayString = state.myReady ? "Не готов" : "Готов";
        boolean both = state.myReady && state.partnerReady;
        confirm.visible = both;
        confirm.enabled = both && !state.myConfirm;
        confirm.displayString = state.myConfirm ? "Ждём..." : "§aОбменять";
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        if (!closedByServer) {
            send(PacketTradeAction.CLOSE, 0, 0, "");
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void updateScreen() {
        chat.updateCursorCounter();
        amount.updateCursorCounter();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == ready) {
            send(PacketTradeAction.READY, 0, 0, "");
        } else if (button == confirm) {
            send(PacketTradeAction.CONFIRM, 0, 0, "");
        }
    }

    @Override
    protected void keyTyped(char typed, int key) {
        if (amountSlot >= 0) {
            // Esc closes only the dialog; digits, editing keys and Enter go to it.
            if (key == Keyboard.KEY_ESCAPE) {
                amountSlot = -1;
            } else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                applyAmount();
            } else if (!Character.isLetter(typed) && typed != ' ' && typed != '-') {
                amount.textboxKeyTyped(typed, key);
            }
            return;
        }
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        if (chat.isFocused()) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                String text = chat.getText()
                    .trim();
                if (!text.isEmpty()) {
                    send(PacketTradeAction.CHAT, 0, 0, text);
                }
                chat.setText("");
            } else {
                chat.textboxKeyTyped(typed, key);
            }
        } else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_T) {
            chat.setFocused(true);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (amountSlot >= 0) {
            if (button == 0 && amountOk.mousePressed(mc, mouseX, mouseY)) {
                amountOk.func_146113_a(mc.getSoundHandler());
                applyAmount();
            } else if (button == 0 && amountCancel.mousePressed(mc, mouseX, mouseY)) {
                amountCancel.func_146113_a(mc.getSoundHandler());
                amountSlot = -1;
            } else {
                amount.mouseClicked(mouseX, mouseY, button);
                amount.setFocused(true);
            }
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
        chat.mouseClicked(mouseX, mouseY, button);
        int offer = gridIndex(mouseX, mouseY, MINE_X);
        if (button == 2) {
            // Middle click: type how many to put on the table (an offer: how many to leave there).
            if (offer >= 0 && offer < state.mySlots.length) {
                openAmount(state.mySlots[offer]);
            } else if (inventorySlot(mouseX, mouseY) >= 0) {
                openAmount(inventorySlot(mouseX, mouseY));
            }
            return;
        }
        if (button != 0 && button != 1) {
            return;
        }
        if (offer >= 0) {
            if (!state.myReady && offer < state.myItems.length) {
                send(PacketTradeAction.REMOVE, offer, button == 0 ? 0 : 1, "");
            }
            return;
        }
        int slot = inventorySlot(mouseX, mouseY);
        if (slot >= 0 && !state.myReady && blockedEpoch(slot) == null) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[slot];
            int left = stack == null ? 0 : stack.stackSize - reserved(slot);
            if (left > 0) {
                send(PacketTradeAction.ADD, slot, button == 0 ? left : 1, "");
            }
        }
    }

    private void send(int action, int a, int count, String text) {
        WorldTravelNetwork.CHANNEL.sendToServer(new PacketTradeAction(action, a, count, text));
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int x = left, y = top;
        drawRect(x - 1, y - 1, x + W + 1, y + H + 1, PANEL_EDGE);
        drawRect(x, y, x + W, y + H, PANEL);
        drawCenteredString(fontRendererObj, "§6Обмен с §b" + state.partner, x + W / 2, y + 6, 0xFFFFFF);

        section(x + MINE_X, y + GRID_Y, state.myReady);
        section(x + THEIRS_X, y + GRID_Y, state.partnerReady);
        fontRendererObj.drawString("Вы отдаёте" + (state.myReady ? " §a✔" : ""), x + MINE_X, y + GRID_Y - 11, 0xE0E0E0);
        fontRendererObj.drawString(
            "§b" + state.partner + "§r отдаёт" + (state.partnerReady ? " §a✔" : ""),
            x + THEIRS_X,
            y + GRID_Y - 11,
            0xE0E0E0);

        String partnerStatus = state.partnerConfirm ? "§aнажал «Обменять»"
            : state.partnerReady ? "§aготов" : "§7ещё выбирает";
        fontRendererObj.drawString(state.partner + ": " + partnerStatus, x + THEIRS_X, y + 112, 0xC0C0C0);
        if (!state.notice.isEmpty()) {
            drawCenteredString(fontRendererObj, state.notice, x + W / 2, y + 131, 0xFFFFFF);
        } else if (state.myReady && state.partnerReady && !state.myConfirm) {
            drawCenteredString(fontRendererObj, "§eОба готовы: проверьте и нажмите «Обменять»", x + W / 2, y + 131, 0);
        }

        fontRendererObj.drawString("Инвентарь", x + MINE_X, y + INV_Y - 10, 0xA0A0A0);
        for (int i = 0; i < 36; i++) {
            int[] at = inventoryPos(i);
            slotFrame(x + at[0], y + at[1]);
        }
        drawChat(x, y);

        super.drawScreen(mouseX, mouseY, partialTicks);
        chat.drawTextBox();

        drawItems(x, y);
        if (amountSlot >= 0) {
            drawAmount(mouseX, mouseY);
        } else {
            drawTooltip(mouseX, mouseY);
        }
    }

    private void section(int sx, int sy, boolean locked) {
        int w = COLS * SLOT, h = ROWS * SLOT;
        drawRect(sx - 3, sy - 3, sx + w + 3, sy + h + 3, locked ? EDGE_READY : SLOT_EDGE);
        drawRect(sx - 2, sy - 2, sx + w + 2, sy + h + 2, locked ? SECTION_READY : SECTION);
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                slotFrame(sx + c * SLOT, sy + r * SLOT);
            }
        }
    }

    private void slotFrame(int sx, int sy) {
        drawRect(sx, sy, sx + 18, sy + 18, SLOT_EDGE);
        drawRect(sx + 1, sy + 1, sx + 17, sy + 17, SLOT_BG);
    }

    private void drawChat(int x, int y) {
        drawRect(x + CHAT_X - 1, y + CHAT_Y - 1, x + CHAT_X + CHAT_W + 1, y + CHAT_BOTTOM + 1, SLOT_EDGE);
        drawRect(x + CHAT_X, y + CHAT_Y, x + CHAT_X + CHAT_W, y + CHAT_BOTTOM, SLOT_BG);
        fontRendererObj.drawString("Чат обмена", x + CHAT_X, y + CHAT_Y - 10, 0xA0A0A0);
        List<String> lines = new ArrayList<>();
        for (String line : state.chat) {
            for (Object part : fontRendererObj.listFormattedStringToWidth(line, CHAT_W - 4)) {
                lines.add((String) part);
            }
        }
        int fit = (CHAT_BOTTOM - CHAT_Y - 2) / 9;
        int from = Math.max(0, lines.size() - fit);
        for (int i = from; i < lines.size(); i++) {
            fontRendererObj.drawString(lines.get(i), x + CHAT_X + 2, y + CHAT_Y + 2 + (i - from) * 9, 0xFFFFFF);
        }
    }

    private void drawItems(int x, int y) {
        GL11.glPushMatrix();
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        itemRender.zLevel = 100F;
        for (int i = 0; i < state.myItems.length; i++) {
            item(state.myItems[i], x + MINE_X + (i % COLS) * SLOT + 1, y + GRID_Y + (i / COLS) * SLOT + 1);
        }
        for (int i = 0; i < state.partnerItems.length && i < COLS * ROWS; i++) {
            item(state.partnerItems[i], x + THEIRS_X + (i % COLS) * SLOT + 1, y + GRID_Y + (i / COLS) * SLOT + 1);
        }
        ItemStack[] inventory = mc.thePlayer.inventory.mainInventory;
        List<int[]> shaded = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory[i];
            if (stack == null) {
                continue;
            }
            int[] at = inventoryPos(i);
            int left = stack.stackSize - reserved(i);
            ItemStack shown = stack.copy();
            shown.stackSize = Math.max(1, left);
            item(shown, x + at[0] + 1, y + at[1] + 1);
            if (left <= 0) {
                shaded.add(new int[] { at[0], at[1], 0xB0101010 });
            } else if (blockedEpoch(i) != null) {
                shaded.add(new int[] { at[0], at[1], 0x80C02020 });
            } else if (left < stack.stackSize) {
                shaded.add(new int[] { at[0], at[1], 0x40E0C040 });
            }
        }
        itemRender.zLevel = 0F;
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        for (int[] s : shaded) {
            drawRect(x + s[0] + 1, y + s[1] + 1, x + s[0] + 17, y + s[1] + 17, s[2]);
        }
        GL11.glPopMatrix();
    }

    private void item(ItemStack stack, int sx, int sy) {
        if (stack == null) {
            return;
        }
        itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stack, sx, sy);
        itemRender.renderItemOverlayIntoGUI(fontRendererObj, mc.getTextureManager(), stack, sx, sy, null);
    }

    @SuppressWarnings("unchecked")
    private void drawTooltip(int mouseX, int mouseY) {
        ItemStack stack = null;
        List<String> extra = new ArrayList<>();
        int offer = gridIndex(mouseX, mouseY, MINE_X);
        int theirs = gridIndex(mouseX, mouseY, THEIRS_X);
        int slot = inventorySlot(mouseX, mouseY);
        if (offer >= 0 && offer < state.myItems.length) {
            stack = state.myItems[offer];
            if (!state.myReady) {
                extra.add("§7ЛКМ — убрать всё, ПКМ — убрать одну");
                extra.add("§7Колёсико — ввести количество");
            }
        } else if (theirs >= 0 && theirs < state.partnerItems.length) {
            stack = state.partnerItems[theirs];
        } else if (slot >= 0) {
            stack = mc.thePlayer.inventory.mainInventory[slot];
            String epoch = blockedEpoch(slot);
            if (stack != null && epoch != null) {
                extra.add("§c" + state.partner + " ещё не достиг эпохи " + epoch);
                extra.add("§cэтот предмет ему передать нельзя");
            } else if (stack != null && !state.myReady) {
                extra.add("§7ЛКМ — выложить всё, ПКМ — выложить одну");
                extra.add("§7Колёсико — ввести количество");
            }
            int reserved = reserved(slot);
            if (stack != null && reserved > 0) {
                extra.add("§eНа столе: " + reserved);
            }
        }
        if (stack == null) {
            return;
        }
        List<String> lines = stack.getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips);
        if (!lines.isEmpty()) {
            lines.set(0, stack.getRarity().rarityColor + lines.get(0));
            for (int i = 1; i < lines.size(); i++) {
                lines.set(i, EnumChatFormatting.GRAY + lines.get(i));
            }
        }
        if (stack.stackSize > 1) {
            lines.add("§fКоличество: " + stack.stackSize);
        }
        lines.addAll(extra);
        drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
        GL11.glDisable(GL11.GL_LIGHTING);
    }

    // ---------------------------------------------------------------- where things are

    /** Index in the 9×4 grid at {@code gx} under the mouse, or -1. */
    private int gridIndex(int mouseX, int mouseY, int gx) {
        int rx = mouseX - left - gx, ry = mouseY - top - GRID_Y;
        if (rx < 0 || ry < 0 || rx >= COLS * SLOT || ry >= ROWS * SLOT) {
            return -1;
        }
        return (ry / SLOT) * COLS + rx / SLOT;
    }

    /** Main inventory slot under the mouse (0–8 the hotbar, 9–35 the rows), or -1. */
    private int inventorySlot(int mouseX, int mouseY) {
        for (int i = 0; i < 36; i++) {
            int[] at = inventoryPos(i);
            int rx = mouseX - left - at[0], ry = mouseY - top - at[1];
            if (rx >= 0 && ry >= 0 && rx < SLOT && ry < SLOT) {
                return i;
            }
        }
        return -1;
    }

    private static int[] inventoryPos(int slot) {
        if (slot < 9) {
            return new int[] { MINE_X + slot * SLOT, HOTBAR_Y };
        }
        int row = (slot - 9) / 9, col = (slot - 9) % 9;
        return new int[] { MINE_X + col * SLOT, INV_Y + row * SLOT };
    }

    private int reserved(int slot) {
        for (int i = 0; i < state.mySlots.length; i++) {
            if (state.mySlots[i] == slot) {
                return state.myItems[i] == null ? 0 : state.myItems[i].stackSize;
            }
        }
        return 0;
    }

    private String blockedEpoch(int slot) {
        for (int i = 0; i < state.blockedSlots.length; i++) {
            if (state.blockedSlots[i] == slot) {
                return state.blockedEpochs[i];
            }
        }
        return null;
    }
}
