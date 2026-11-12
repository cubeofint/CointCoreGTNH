package coint.client;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

import org.lwjgl.input.Keyboard;

import coint.migration.MigrationNetwork;

/** Stepwise admin UI. Every operation uses the ordinary server-side permission-checked command. */
public final class GuiMigration extends GuiScreen {

    private final MigrationNetwork.State state;
    private GuiTextField name;
    private int source, target, archive, request;
    private String confirming;
    private String completionPrefix;
    private int completionIndex;
    private GuiButton sourceButton, targetButton, exportButton, archiveButton, queueButton, requestButton, importButton;

    public GuiMigration(MigrationNetwork.State state) {
        this.state = state;
        target = state.nodes.size() > 1 ? 1 : 0;
    }

    public static void receive(MigrationNetwork.State state) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> { if (mc.thePlayer != null) mc.displayGuiScreen(new GuiMigration(state)); });
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        int w = panelWidth();
        int x = (width - w) / 2;
        int half = (w - 4) / 2;
        int y = top();
        String previous = name == null ? "" : name.getText();
        name = new GuiTextField(fontRendererObj, x + 34, y + 48, Math.max(30, half - 38), 18);
        name.setMaxStringLength(16);
        name.setText(previous);
        name.setFocused(true);
        buttonList.clear();
        sourceButton = add(1, x, y + 20, half, "");
        add(2, x + half + 4, y + 20, w - half - 4, "Перейти на источник");
        targetButton = add(3, x + half + 4, y + 47, w - half - 4, "");
        exportButton = add(4, x, y + 72, w, "Экспорт команды → очередь цели");
        archiveButton = add(5, x, y + 98, half, "");
        queueButton = add(6, x + half + 4, y + 98, w - half - 4, "ZIP → очередь");
        requestButton = add(7, x, y + 127, w, "");
        importButton = add(8, x, y + 165, w, "");
        add(9, x, y + 195, half, "Обновить");
        add(10, x + half + 4, y + 195, w - half - 4, "Закрыть");
        refresh();
    }

    @SuppressWarnings("unchecked")
    private GuiButton add(int id, int x, int y, int w, String title) {
        GuiButton button = new GuiButton(id, x, y, w, 20, title);
        buttonList.add(button);
        return button;
    }

    private int top() {
        return Math.max(2, (height - 225) / 2);
    }

    private int panelWidth() {
        return Math.max(100, Math.min(420, width - 16));
    }

    private String node(int index) {
        return state.nodes.isEmpty() ? state.current : state.nodes.get(index % state.nodes.size());
    }

    private String[] selectedRequest() {
        return state.requests.isEmpty() ? null : state.requests.get(request % state.requests.size());
    }

    private void refresh() {
        sourceButton.displayString = "Источник: " + node(source);
        targetButton.displayString = "Цель: " + node(target);
        boolean here = state.current.equals(node(source));
        boolean other = !state.current.equals(node(target));
        exportButton.enabled = here && other
            && name.getText()
                .matches("[A-Za-z0-9_]{1,16}");
        archiveButton.displayString = state.archives.isEmpty() ? "Нет готовых ZIP"
            : fontRendererObj
                .trimStringToWidth(state.archives.get(archive % state.archives.size()), archiveButton.width - 10);
        queueButton.enabled = here && other && !state.archives.isEmpty();
        String[] row = selectedRequest();
        requestButton.displayString = row == null ? "Нет входящих запросов"
            : fontRendererObj.trimStringToWidth(
                (request + 1) + "/" + state.requests.size() + " " + row[3] + " " + row[1],
                panelWidth() - 10);
        importButton.enabled = row != null && "pending".equals(row[3]);
        importButton.displayString = confirming == null ? "Проверить и подтвердить импорт"
            : "ПОДТВЕРЖДАЮ замену данных на " + state.current;
        for (Object value : buttonList) {
            GuiButton button = (GuiButton) value;
            button.displayString = fontRendererObj.trimStringToWidth(button.displayString, button.width - 8);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 1:
                source = (source + 1) % Math.max(1, state.nodes.size());
                break;
            case 2:
                if (!state.current.equals(node(source))) command("/server " + node(source));
                break;
            case 3:
                target = (target + 1) % Math.max(1, state.nodes.size());
                break;
            case 4:
                command("/migrate export " + name.getText() + " " + node(target));
                return;
            case 5:
                archive = (archive + 1) % Math.max(1, state.archives.size());
                break;
            case 6:
                command("/migrate queue " + state.archives.get(archive) + " " + node(target));
                return;
            case 7:
                request = (request + 1) % Math.max(1, state.requests.size());
                confirming = null;
                break;
            case 8:
                String[] row = selectedRequest();
                if (row == null || !"pending".equals(row[3])) return;
                if (row[0].equals(confirming)) {
                    command("/migrate accept " + row[0] + " confirm");
                    return;
                }
                confirming = row[0];
                break;
            case 9:
                command("/migrate ui");
                return;
            case 10:
                mc.displayGuiScreen(null);
                return;
            default:
                break;
        }
        refresh();
    }

    private void command(String command) {
        if (command.length() > 100) {
            mc.thePlayer.addChatMessage(
                new net.minecraft.util.ChatComponentText(
                    "§cКоманда длиннее лимита чата 1.7.10 (100). Используйте консоль сервера."));
            mc.displayGuiScreen(null);
            return;
        }
        mc.thePlayer.sendChatMessage(command);
        mc.displayGuiScreen(null);
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_TAB && name.isFocused()) {
            if (completionPrefix == null) {
                completionPrefix = name.getText()
                    .toLowerCase(Locale.ROOT);
                completionIndex = 0;
            }
            for (int i = 0; i < state.names.size(); i++) {
                String candidate = state.names.get(completionIndex++ % state.names.size());
                if (candidate.toLowerCase(Locale.ROOT)
                    .startsWith(completionPrefix)) {
                    name.setText(candidate);
                    break;
                }
            }
        } else {
            completionPrefix = null;
            if (!name.textboxKeyTyped(character, key)) super.keyTyped(character, key);
        }
        refresh();
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        name.mouseClicked(x, y, button);
    }

    @Override
    public void updateScreen() {
        name.updateCursorCounter();
    }

    @Override
    public void drawScreen(int x, int y, float partialTicks) {
        drawDefaultBackground();
        int left = (width - panelWidth()) / 2;
        int top = top();
        drawCenteredString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth("Миграция · текущий узел: " + state.current, panelWidth()),
            width / 2,
            top + 3,
            0xFFFFFF);
        fontRendererObj.drawString("Ник:", left, top + 53, 0xFFFFFF);
        name.drawTextBox();
        String[] row = selectedRequest();
        String detail = row == null ? "Импорт запускается только явным подтверждением"
            : row[2] + " → " + state.current + " | " + row[0];
        drawCenteredString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(detail, panelWidth()),
            width / 2,
            top + 151,
            0xCCCCCC);
        if (confirming != null) drawCenteredString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth("Команда офлайн! Данные заменяются, backup локальный.", panelWidth()),
            width / 2,
            top + 186,
            0xFF7777);
        super.drawScreen(x, y, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }
}
