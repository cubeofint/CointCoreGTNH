package coint.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.IIcon;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import coint.cluster.RemoteMe;
import coint.network.PacketRemoteMeAction;
import coint.network.WorldTravelNetwork;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The craft confirm screen for a remote network, after AE2's: what the plan takes from storage, what it crafts and what
 * is missing, how many bytes it needs, and the crafting processor to run it on. The terminal window stays open behind
 * it and comes back when this closes.
 */
@SideOnly(Side.CLIENT)
public class GuiRemoteCraftConfirm extends GuiScreen {

    private static final int W = 238, H = 206, COLS = 3, ROWS = 5, CELL_W = 74, CELL_H = 26;
    private static final int GRID_X = 9, GRID_Y = 34;

    private static final class Line {

        ItemStack stack;
        Fluid fluid;
        long take, make, missing;
        String name;
    }

    private static final class Cpu {

        String name;
        long bytes;
        int coProcessors;
        boolean busy;
    }

    private final GuiScreen parent;
    private final int windowId;
    private final ItemStack kind;
    private final long amount;

    /** Until the plan arrives: planning. */
    private boolean received, startable, cut;
    private long planId, bytes;
    private String error = "";
    private final List<Line> lines = new ArrayList<>();
    private final List<Cpu> cpus = new ArrayList<>();
    /** -1: any free processor. */
    private int cpu = -1;
    private int scroll;
    private GuiButton cpuButton, startButton;

    private GuiRemoteCraftConfirm(GuiScreen parent, int windowId, ItemStack kind, long amount) {
        this.parent = parent;
        this.windowId = windowId;
        this.kind = kind;
        this.amount = amount;
    }

    /** Asks the remote network to plan the order and shows this screen until the plan arrives. */
    public static void open(GuiScreen parent, int windowId, ItemStack kind, long amount) {
        WorldTravelNetwork.CHANNEL.sendToServer(
            new PacketRemoteMeAction(windowId, RemoteMe.ACTION_PLAN, kind, (int) Math.min(amount, RemoteMe.MAX_CRAFT)));
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiRemoteCraftConfirm(parent, windowId, kind, amount));
    }

    public static void receive(int windowId, long planId, boolean startable, ItemStack kind, long amount, byte[] data) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> {
            if (mc.currentScreen instanceof GuiRemoteCraftConfirm screen && screen.windowId == windowId
                && !screen.received
                && kind != null
                && ItemStack.areItemStacksEqual(screen.kind, kind)) {
                screen.accept(planId, startable, data);
            }
        });
    }

    private void accept(long id, boolean canStart, byte[] data) {
        received = true;
        planId = id;
        startable = canStart;
        if (data.length == 0) {
            error = "Не удалось рассчитать крафт";
            startable = false;
            updateButtons();
            return;
        }
        NBTTagCompound root;
        try {
            root = CompressedStreamTools.readCompressed(new ByteArrayInputStream(data));
        } catch (IOException e) {
            error = "Не удалось прочитать план";
            startable = false;
            updateButtons();
            return;
        }
        bytes = root.getLong("b");
        cut = root.getBoolean("cut");
        if (root.hasKey("e")) {
            error = root.getString("e");
        }
        NBTTagList list = root.getTagList("l", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound e = list.getCompoundTagAt(i);
            Line line = new Line();
            if (e.hasKey("f")) {
                line.fluid = FluidRegistry.getFluid(e.getString("f"));
                if (line.fluid == null) {
                    continue;
                }
                line.name = line.fluid.getLocalizedName(new net.minecraftforge.fluids.FluidStack(line.fluid, 1));
            } else {
                Item item = (Item) Item.itemRegistry.getObject(e.getString("i"));
                if (item == null) {
                    continue;
                }
                line.stack = new ItemStack(item, 1, e.getShort("d"));
                if (e.hasKey("t")) {
                    line.stack.setTagCompound(e.getCompoundTag("t"));
                }
                try {
                    line.name = line.stack.getDisplayName();
                } catch (RuntimeException ex) {
                    line.name = e.getString("i");
                }
            }
            line.take = e.getLong("x");
            line.make = e.getLong("c");
            line.missing = e.getLong("m");
            lines.add(line);
        }
        // Missing first, as AE2 shows them.
        lines.sort((a, b) -> Boolean.compare(b.missing > 0, a.missing > 0));
        NBTTagList cpuList = root.getTagList("cpus", 10);
        for (int i = 0; i < cpuList.tagCount(); i++) {
            NBTTagCompound c = cpuList.getCompoundTagAt(i);
            Cpu cpu = new Cpu();
            cpu.name = c.getString("n");
            cpu.bytes = c.getLong("b");
            cpu.coProcessors = c.getInteger("p");
            cpu.busy = c.getBoolean("busy");
            cpus.add(cpu);
        }
        updateButtons();
    }

    @Override
    public void initGui() {
        int x = (width - W) / 2, y = (height - H) / 2;
        buttonList.clear();
        buttonList.add(new GuiButton(0, x + 8, y + H - 26, 52, 20, "Отмена"));
        cpuButton = new GuiButton(1, x + 64, y + H - 26, 110, 20, "");
        startButton = new GuiButton(2, x + W - 60, y + H - 26, 52, 20, "Запуск");
        buttonList.add(cpuButton);
        buttonList.add(startButton);
        updateButtons();
    }

    private void updateButtons() {
        if (cpuButton == null) {
            return;
        }
        cpuButton.enabled = received && startable && !cpus.isEmpty();
        cpuButton.displayString = cpu < 0 ? "Процессор: авто" : cpuLabel(cpu);
        startButton.enabled = received && startable && (cpu < 0 || suitable(cpus.get(cpu)));
    }

    private String cpuLabel(int index) {
        Cpu c = cpus.get(index);
        String name = c.name.isEmpty() ? "ЦП #" + (index + 1) : c.name;
        String color = c.busy ? "§c" : !suitable(c) ? "§e" : "";
        return fontRendererObj.trimStringToWidth(color + name, 100);
    }

    private boolean suitable(Cpu c) {
        return !c.busy && c.bytes >= bytes;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            back();
        } else if (button.id == 1) {
            // Cycles: auto, then each processor.
            cpu = cpu + 1 >= cpus.size() ? -1 : cpu + 1;
            updateButtons();
        } else if (button.id == 2 && startable) {
            WorldTravelNetwork.CHANNEL
                .sendToServer(new PacketRemoteMeAction(windowId, RemoteMe.ACTION_START, kind, cpu, planId));
            back();
        }
    }

    private void back() {
        mc.displayGuiScreen(parent);
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            back();
        } else if ((key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) && startButton.enabled) {
            actionPerformed(startButton);
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int max = Math.max(0, (lines.size() + COLS - 1) / COLS - ROWS);
            scroll = Math.max(0, Math.min(max, scroll + (wheel > 0 ? -1 : 1)));
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int x = (width - W) / 2, y = (height - H) / 2;
        drawRect(x - 1, y - 1, x + W + 1, y + H + 1, 0xFF000000);
        drawRect(x, y, x + W, y + H, 0xFFC6C6C6);
        String title;
        if (!received) {
            title = "Расчёт крафта…";
        } else if (!startable) {
            title = "§4Не хватает ресурсов";
        } else {
            title = "Подтверждение крафта";
        }
        fontRendererObj.drawString(title, x + 8, y + 6, 0x404040);
        String what = String.format(Locale.ROOT, "%,d", amount) + " × " + kind.getDisplayName();
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(what, W - 16), x + 8, y + 16, 0x202080);
        if (received) {
            String info = "Байт: " + String.format(Locale.ROOT, "%,d", bytes) + (cut ? "  §8(список урезан)" : "");
            fontRendererObj.drawString(info, x + W - 8 - fontRendererObj.getStringWidth(info), y + 6, 0x404040);
        }
        drawRect(x + GRID_X - 1, y + GRID_Y - 1, x + GRID_X + COLS * CELL_W, y + GRID_Y + ROWS * CELL_H, 0xFF8B8B8B);
        Line hovered = null;
        int first = scroll * COLS;
        for (int i = 0; i < COLS * ROWS && first + i < lines.size(); i++) {
            Line line = lines.get(first + i);
            int cx = x + GRID_X + i % COLS * CELL_W, cy = y + GRID_Y + i / COLS * CELL_H;
            drawRect(cx, cy, cx + CELL_W - 1, cy + CELL_H - 1, line.missing > 0 ? 0xFFE0A0A0 : 0xFFD8D8D8);
            drawIcon(line, cx + 2, cy + 5);
            GL11.glPushMatrix();
            GL11.glTranslatef(cx + 20, cy + 2, 0);
            GL11.glScalef(0.5F, 0.5F, 1);
            int row = 0;
            if (line.take > 0) {
                fontRendererObj.drawString("Есть: " + compact(line.take), 0, row++ * 10, 0x404040);
            }
            if (line.make > 0) {
                fontRendererObj.drawString("Скрафтить: " + compact(line.make), 0, row++ * 10, 0x206020);
            }
            if (line.missing > 0) {
                fontRendererObj.drawString("Нет: " + compact(line.missing), 0, row * 10, 0xA00000);
            }
            GL11.glPopMatrix();
            if (mouseX >= cx && mouseX < cx + CELL_W && mouseY >= cy && mouseY < cy + CELL_H) {
                hovered = line;
            }
        }
        if (!received) {
            String wait = "Сеть на другом сервере считает план…";
            fontRendererObj.drawString(wait, x + (W - fontRendererObj.getStringWidth(wait)) / 2, y + 90, 0x404040);
        } else if (!error.isEmpty() && lines.isEmpty()) {
            fontRendererObj.drawString(error, x + (W - fontRendererObj.getStringWidth(error)) / 2, y + 90, 0xA00000);
        }
        GL11.glColor4f(1, 1, 1, 1);
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (hovered != null) {
            List<String> tip = new ArrayList<>();
            tip.add(hovered.name);
            if (hovered.take > 0) {
                tip.add("§7Из хранилища: §f" + String.format(Locale.ROOT, "%,d", hovered.take));
            }
            if (hovered.make > 0) {
                tip.add("§7Будет скрафчено: §a" + String.format(Locale.ROOT, "%,d", hovered.make));
            }
            if (hovered.missing > 0) {
                tip.add("§7Не хватает: §c" + String.format(Locale.ROOT, "%,d", hovered.missing));
            }
            drawHoveringText(tip, mouseX, mouseY, fontRendererObj);
        } else if (cpuButton.func_146115_a() && !cpus.isEmpty()) {
            drawHoveringText(cpuTip(), mouseX, mouseY, fontRendererObj);
        }
    }

    private List<String> cpuTip() {
        List<String> tip = new ArrayList<>();
        if (cpu < 0) {
            tip.add("Любой свободный процессор");
        } else {
            Cpu c = cpus.get(cpu);
            tip.add(c.name.isEmpty() ? "ЦП #" + (cpu + 1) : c.name);
            tip.add("§7Память: §f" + String.format(Locale.ROOT, "%,d", c.bytes) + " байт");
            tip.add("§7Сопроцессоры: §f" + c.coProcessors);
            if (c.busy) {
                tip.add("§cЗанят");
            } else if (c.bytes < bytes) {
                tip.add("§eМало памяти для этого плана");
            }
        }
        tip.add("§8Клик — следующий процессор");
        return tip;
    }

    private void drawIcon(Line line, int x, int y) {
        if (line.stack != null) {
            RenderHelper.enableGUIStandardItemLighting();
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            try {
                itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), line.stack, x, y);
            } catch (RuntimeException e) {
                // A failing renderer leaves the cell without an icon.
            }
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            GL11.glDisable(GL11.GL_LIGHTING);
        } else if (line.fluid != null) {
            IIcon icon = line.fluid.getIcon();
            if (icon != null) {
                mc.getTextureManager()
                    .bindTexture(TextureMap.locationBlocksTexture);
                int color = line.fluid.getColor();
                GL11.glColor4f((color >> 16 & 255) / 255F, (color >> 8 & 255) / 255F, (color & 255) / 255F, 1);
                drawTexturedModelRectFromIcon(x, y, icon, 16, 16);
                GL11.glColor4f(1, 1, 1, 1);
            }
        }
    }

    private static String compact(long value) {
        if (value < 10_000) {
            return String.valueOf(value);
        }
        if (value < 1_000_000) {
            return value / 1000 + "k";
        }
        if (value < 1_000_000_000L) {
            return value / 1_000_000 + "M";
        }
        return value / 1_000_000_000L + "G";
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
