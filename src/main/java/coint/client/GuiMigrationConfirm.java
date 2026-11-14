package coint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import coint.migration.MigrationNetwork;

/** Closing the screen is a refusal, never consent. Commands are revalidated on the server. */
public final class GuiMigrationConfirm extends GuiScreen {

    private final MigrationNetwork.Confirm request;
    private boolean answered;

    private GuiMigrationConfirm(MigrationNetwork.Confirm request) {
        this.request = request;
    }

    public static void receive(MigrationNetwork.Confirm request) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> {
            if (mc.thePlayer == null) return;
            if (mc.currentScreen instanceof GuiMigrationConfirm current && current.request.id.equals(request.id))
                return;
            mc.displayGuiScreen(new GuiMigrationConfirm(request));
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        buttonList.clear();
        int w = Math.min(140, (width - 24) / 2);
        buttonList.add(new GuiButton(0, width / 2 - w - 4, height / 2 + 42, w, 20, "Перенести команду"));
        buttonList.add(new GuiButton(1, width / 2 + 4, height / 2 + 42, w, 20, "Позже"));
    }

    private void answer(boolean yes) {
        if (answered) return;
        answered = true;
        if (mc.thePlayer != null)
            mc.thePlayer.sendChatMessage("/migrate-confirm " + request.id + (yes ? " yes" : " no"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        answer(button.id == 0);
        mc.displayGuiScreen(null);
    }

    @Override
    public void onGuiClosed() {
        answer(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int x, int y, float partial) {
        drawDefaultBackground();
        drawCenteredString(
            fontRendererObj,
            "Переход команды на " + request.target,
            width / 2,
            height / 2 - 65,
            0xFFFFFF);
        int w = Math.min(400, width - 24);
        fontRendererObj.drawSplitString(
            "Будет перенесена вся команда и её база. После проверки всех участников предупредят и отключат через 15 секунд. После завершения подключитесь снова.",
            (width - w) / 2,
            height / 2 - 42,
            w,
            0xFFFFFF);
        drawCenteredString(fontRendererObj, "Повторно открыть: /migrate ui", width / 2, height / 2 + 22, 0xAAAAAA);
        super.drawScreen(x, y, partial);
    }
}
