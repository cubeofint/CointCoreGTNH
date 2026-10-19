package coint.supply;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import coint.network.PacketSupplySettings;
import coint.network.WorldTravelNetwork;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The window of a remote buffer, on a chest background: filters, their stocks, the target and threshold of the filter
 * picked, the send slots, the inventory. The threshold can be typed as a number or as a share of the target ("25%"),
 * turned into a number on Enter.
 */
@SideOnly(Side.CLIENT)
public class GuiSupplyBuffer extends GuiContainer {

    private static final ResourceLocation CHEST = new ResourceLocation("textures/gui/container/generic_54.png");
    private static final int ROWS = 5;
    private static final int FIELDS_Y = 56;

    private final ContainerSupplyBuffer container;
    private GuiTextField targetField, thresholdField;
    /** The filter whose target and threshold the fields show. */
    private int selected;
    private String shownFor = "";

    public GuiSupplyBuffer(InventoryPlayer playerInventory, TileSupplyBuffer tile) {
        super(new ContainerSupplyBuffer(playerInventory, tile));
        container = (ContainerSupplyBuffer) inventorySlots;
        ySize = 114 + ROWS * 18;
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        targetField = new GuiTextField(fontRendererObj, guiLeft + 36, guiTop + FIELDS_Y, 50, 12);
        thresholdField = new GuiTextField(fontRendererObj, guiLeft + 122, guiTop + FIELDS_Y, 46, 12);
        targetField.setMaxStringLength(14);
        thresholdField.setMaxStringLength(14);
        shownFor = "";
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        targetField.updateCursorCounter();
        thresholdField.updateCursorCounter();
        // What the server says goes into the fields, unless one is being typed in.
        String now = selected + ":" + container.target[selected] + ":" + container.threshold[selected];
        if (!targetField.isFocused() && !thresholdField.isFocused() && !now.equals(shownFor)) {
            targetField.setText(String.valueOf(container.target[selected]));
            thresholdField.setText(String.valueOf(container.threshold[selected]));
            shownFor = now;
        }
        boolean active = container.tile.getFilter(selected) != null;
        targetField.setEnabled(active);
        thresholdField.setEnabled(active);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GL11.glColor4f(1, 1, 1, 1);
        mc.getTextureManager()
            .bindTexture(CHEST);
        int x = guiLeft, y = guiTop;
        drawTexturedModalRect(x, y, 0, 0, xSize, ROWS * 18 + 17);
        drawTexturedModalRect(x, y + ROWS * 18 + 17, 0, 126, xSize, 96);
        // The row of the fields has no slots.
        drawRect(x + 7, y + 53, x + 169, y + 71, 0xFFC6C6C6);
        // The filter row stands apart, and the picked filter more.
        drawRect(x + 7, y + 17, x + 169, y + 35, 0x40306090);
        Slot picked = (Slot) inventorySlots.inventorySlots.get(ContainerSupplyBuffer.FILTER_SLOTS + selected);
        int px = x + picked.xDisplayPosition, py = y + picked.yDisplayPosition;
        drawRect(px - 1, py - 1, px + 17, py + 17, 0x80FFD040);
        GL11.glColor4f(1, 1, 1, 1);
        targetField.drawTextBox();
        thresholdField.drawTextBox();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        String state = container.online ? "§2Связь есть" : "§4Нет связи с главной ME";
        fontRendererObj.drawString("Буфер снабжения", 8, 6, 0x404040);
        fontRendererObj.drawString(state, xSize - 8 - fontRendererObj.getStringWidth(state), 6, 0x404040);
        fontRendererObj.drawString("Цель", 9, FIELDS_Y + 2, 0x404040);
        fontRendererObj.drawString("Порог", 91, FIELDS_Y + 2, 0x404040);
        fontRendererObj.drawString("Отправка в главную ME  §8в пути: " + container.pending, 8, 110, 0x404040);
        // The stock slots are drawn here, not by the slots: the stock can be far more than a stack.
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        for (int i = 0; i < TileSupplyBuffer.FILTERS; i++) {
            ItemStack filter = container.tile.getFilter(i);
            if (filter == null || container.stock[i] <= 0) {
                continue;
            }
            FluidStack fluid = container.tile.getFilterFluid(i);
            if (fluid != null) {
                drawFluid(fluid, 8 + i * 18, 36);
                RenderHelper.enableGUIStandardItemLighting();
                continue;
            }
            itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), filter, 8 + i * 18, 36);
        }
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        for (int i = 0; i < TileSupplyBuffer.FILTERS; i++) {
            if (container.tile.getFilter(i) == null) {
                continue;
            }
            String text = compact(container.stock[i]);
            int color = container.stock[i] <= container.threshold[i] ? 0xFF6060 : 0xFFFFFF;
            float scale = text.length() > 3 ? 0.75F : 1F;
            GL11.glPushMatrix();
            GL11.glTranslatef(8 + i * 18 + 17, 36 + 17, 0);
            GL11.glScalef(scale, scale, 1);
            fontRendererObj.drawStringWithShadow(text, -fontRendererObj.getStringWidth(text), -8, color);
            GL11.glPopMatrix();
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        // The text leaves its color set: what is drawn next would be tinted with it.
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        List<String> lines = new ArrayList<>();
        int stockSlot = slotUnder(ContainerSupplyBuffer.STOCK_SLOTS, mouseX, mouseY);
        int filter = slotUnder(ContainerSupplyBuffer.FILTER_SLOTS, mouseX, mouseY);
        if (stockSlot >= 0 && container.tile.getFilter(stockSlot) != null) {
            lines.add(name(stockSlot));
            lines.add(
                "§7Запас: §f" + container.stock[stockSlot]
                    + unit(stockSlot)
                    + " §7/ цель §f"
                    + container.target[stockSlot]
                    + unit(stockSlot));
            lines.add(
                container.tile.getFilterFluid(stockSlot) != null ? "§7Выкачать: труба с любой стороны"
                    : "§7Забрать: клик, Shift+клик или труба с любой стороны");
        } else if (filter >= 0 && heldFluid() != null) {
            lines.add("§eКлик§7 — фильтр на сам предмет");
            lines.add("§eCtrl + клик§7 — запас жидкости §b" + heldFluid().getLocalizedName());
        } else if (filter >= 0) {
            if (container.tile.getFilter(filter) == null) {
                lines.add("§7Фильтр пуст");
                lines.add("§7Кликните предметом, чтобы держать его запас здесь");
                lines.add("§7Ctrl + клик ведром/ячейкой или жидкость из NEI — запас жидкости");
            } else {
                lines.add(name(filter));
                lines.add(
                    "§7Запас: §f" + container.stock[filter]
                        + unit(filter)
                        + " §7/ цель §f"
                        + container.target[filter]
                        + unit(filter));
                lines.add("§7Дозаказ, когда запас ≤ §f" + container.threshold[filter] + unit(filter));
                lines.add("§eЛКМ — выбрать (цель и порог ниже)");
                lines.add("§eКолесо — быстро сменить цель");
                lines.add("§eПКМ пустой рукой — убрать фильтр");
            }
        } else if (inside(thresholdField, mouseX, mouseY)) {
            lines.add("§7Порог дозаказа: числом (§f45§7)");
            lines.add("§7или в процентах от цели (§f25%§7), затем Enter");
        } else if (inside(targetField, mouseX, mouseY)) {
            lines.add("§7Сколько держать в запасе, затем Enter");
        } else if (!container.error.isEmpty() && mouseY < guiTop + 16 && mouseY >= guiTop) {
            lines.add("§cОшибка: " + container.error);
        }
        if (!lines.isEmpty()) {
            drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        int filter = slotUnder(ContainerSupplyBuffer.FILTER_SLOTS, mouseX, mouseY);
        ItemStack held = mc.thePlayer.inventory.getItemStack();
        if (filter >= 0 && button == 0 && held == null) {
            select(filter);
        }
        // Ctrl with a container: the fluid in it, as in AE2.
        if (filter >= 0 && held != null && isCtrlKeyDown() && SupplyFluids.containerFluid(held) != null) {
            ItemStack one = held.copy();
            one.stackSize = 1;
            WorldTravelNetwork.CHANNEL
                .sendToServer(new coint.network.PacketSupplyFilter(container.windowId, filter, one, true));
            select(filter);
            return;
        }
        targetField.mouseClicked(mouseX, mouseY, button);
        thresholdField.mouseClicked(mouseX, mouseY, button);
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void keyTyped(char c, int key) {
        GuiTextField field = targetField.isFocused() ? targetField : thresholdField.isFocused() ? thresholdField : null;
        if (field == null) {
            super.keyTyped(c, key);
            return;
        }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            apply();
            field.setFocused(false);
        } else if (key == Keyboard.KEY_ESCAPE) {
            field.setFocused(false);
            shownFor = "";
        } else if (key == Keyboard.KEY_TAB) {
            targetField.setFocused(field == thresholdField);
            thresholdField.setFocused(field == targetField);
        } else if (Character.isDigit(c) || c == '%'
            || c == '.'
            || "kmgtKMGTкмгтКМГТ".indexOf(c) >= 0
            || key == Keyboard.KEY_BACK
            || key == Keyboard.KEY_DELETE
            || key == Keyboard.KEY_LEFT
            || key == Keyboard.KEY_RIGHT
            || key == Keyboard.KEY_HOME
            || key == Keyboard.KEY_END
            || key == Keyboard.KEY_A && isCtrlKeyDown()) {
                field.textboxKeyTyped(c, key);
            }
    }

    /** Sends what the fields say; the threshold in percent becomes a count of the target. */
    private void apply() {
        if (container.tile.getFilter(selected) == null) {
            return;
        }
        long target = parse(targetField.getText(), container.target[selected]);
        if (target <= 0) {
            target = container.target[selected];
        }
        target = Math.min(target, TileSupplyBuffer.MAX_TARGET);
        long threshold = parse(thresholdField.getText(), target);
        if (threshold < 0) {
            threshold = container.threshold[selected];
        }
        threshold = Math.min(threshold, target);
        targetField.setText(String.valueOf(target));
        thresholdField.setText(String.valueOf(threshold));
        container.target[selected] = target;
        container.threshold[selected] = threshold;
        shownFor = selected + ":" + target + ":" + threshold;
        WorldTravelNetwork.CHANNEL
            .sendToServer(new PacketSupplySettings(container.windowId, selected, target, threshold));
    }

    /**
     * A count, or a share of {@code of} when it ends in %; -1 when it can't be read. A count may end in K, M, G or T
     * (thousand, million, billion, trillion; Russian letters too) and have a fraction: 1.5K is 1500.
     */
    static long parse(String text, long of) {
        String t = text.trim()
            .replace(',', '.');
        if (t.isEmpty()) {
            return -1;
        }
        try {
            if (t.endsWith("%")) {
                double percent = Double.parseDouble(
                    t.substring(0, t.length() - 1)
                        .trim());
                return Math.round(of * Math.max(0, Math.min(100, percent)) / 100.0);
            }
            double scale = 1;
            switch (Character.toLowerCase(t.charAt(t.length() - 1))) {
                case 'k', 'к' -> scale = 1e3;
                case 'm', 'м' -> scale = 1e6;
                case 'g', 'г' -> scale = 1e9;
                case 't', 'т' -> scale = 1e12;
                default -> {}
            }
            if (scale > 1) {
                t = t.substring(0, t.length() - 1)
                    .trim();
            }
            double value = Double.parseDouble(t) * scale;
            if (value < 0 || Double.isNaN(value)) {
                return -1;
            }
            return value >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** An item dropped from NEI: the filter under it takes it. */
    boolean dropFilter(int mouseX, int mouseY, ItemStack item) {
        int filter = slotUnder(ContainerSupplyBuffer.FILTER_SLOTS, mouseX, mouseY);
        if (filter < 0 || item == null) {
            return false;
        }
        ItemStack one = item.copy();
        one.stackSize = 1;
        WorldTravelNetwork.CHANNEL
            .sendToServer(new coint.network.PacketSupplyFilter(container.windowId, filter, one, isCtrlKeyDown()));
        select(filter);
        return true;
    }

    private void select(int filter) {
        if (targetField.isFocused() || thresholdField.isFocused()) {
            apply();
            targetField.setFocused(false);
            thresholdField.setFocused(false);
        }
        selected = filter;
        shownFor = "";
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        int filter = slotUnder(ContainerSupplyBuffer.FILTER_SLOTS, mouseX, mouseY);
        if (filter >= 0 && container.tile.getFilter(filter) != null) {
            mc.playerController.sendEnchantPacket(container.windowId, filter * 2 + (wheel > 0 ? 1 : 0));
        }
    }

    /** A fluid stock: its still texture, tinted with its color. */
    private void drawFluid(FluidStack fluid, int x, int y) {
        IIcon icon = fluid.getFluid()
            .getIcon(fluid);
        if (icon == null) {
            return;
        }
        mc.getTextureManager()
            .bindTexture(TextureMap.locationBlocksTexture);
        int color = fluid.getFluid()
            .getColor(fluid);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glColor4f((color >> 16 & 255) / 255F, (color >> 8 & 255) / 255F, (color & 255) / 255F, 1);
        drawTexturedModelRectFromIcon(x, y, icon, 16, 16);
        GL11.glColor4f(1, 1, 1, 1);
    }

    /** What a filter keeps, by name: the fluid's for a fluid filter. */
    private String name(int filter) {
        FluidStack fluid = container.tile.getFilterFluid(filter);
        return fluid != null ? fluid.getLocalizedName()
            : container.tile.getFilter(filter)
                .getDisplayName();
    }

    private String unit(int filter) {
        return container.tile.getFilterFluid(filter) != null ? " mB" : "";
    }

    /** Which of the nine slots from {@code first} is under the mouse, or -1. */
    private int slotUnder(int first, int mouseX, int mouseY) {
        for (int i = 0; i < TileSupplyBuffer.FILTERS; i++) {
            Slot slot = (Slot) inventorySlots.inventorySlots.get(first + i);
            int x = guiLeft + slot.xDisplayPosition, y = guiTop + slot.yDisplayPosition;
            if (mouseX >= x - 1 && mouseX < x + 17 && mouseY >= y - 1 && mouseY < y + 17) {
                return i;
            }
        }
        return -1;
    }

    private static boolean inside(GuiTextField field, int mouseX, int mouseY) {
        return mouseX >= field.xPosition && mouseX < field.xPosition + field.width
            && mouseY >= field.yPosition
            && mouseY < field.yPosition + field.height;
    }

    private static String compact(long value) {
        if (value < 1000) {
            return String.valueOf(value);
        }
        if (value < 10_000) {
            return String.format(Locale.ROOT, "%.1fk", value / 1000.0)
                .replace(".0k", "k");
        }
        if (value < 1_000_000) {
            return value / 1000 + "k";
        }
        String[] units = { "M", "G", "T" };
        double scaled = value / 1_000_000.0;
        int unit = 0;
        while (scaled >= 1000 && unit < units.length - 1) {
            scaled /= 1000;
            unit++;
        }
        return (scaled < 10 ? String.format(Locale.ROOT, "%.1f", scaled)
            .replace(".0", "") : String.valueOf((long) scaled)) + units[unit];
    }

    /** The fluid in the container held on the cursor, or null. */
    private FluidStack heldFluid() {
        return SupplyFluids.containerFluid(mc.thePlayer.inventory.getItemStack());
    }
}
