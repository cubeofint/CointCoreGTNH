package coint.client;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentText;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import coint.CointCore;
import coint.util.NbtAuditLimit;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Client-only, read-only export. Tooltip hooks and item renderers always run on the render thread. */
public final class CommandKitManifest extends CommandBase {

    private static Job pending;

    @Override
    public String getCommandName() {
        return "kitmanifest";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/kitmanifest <kit-id> [held|inventory]";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1 || args.length > 2
            || !args[0].matches("[a-z0-9][a-z0-9._-]{0,63}")
            || (args.length == 2 && !args[1].equals("held") && !args[1].equals("inventory")))
            throw new WrongUsageException(getCommandUsage(sender));
        if (pending != null) throw new WrongUsageException("Дождитесь окончания предыдущего экспорта");
        Minecraft mc = Minecraft.getMinecraft();
        try {
            Job job = new Job();
            job.id = args[0];
            job.directory = mc.mcDataDir.toPath()
                .resolve("cointcore/kit-manifests")
                .resolve(job.id + "-" + java.util.UUID.randomUUID());
            if (args.length == 2 && args[1].equals("inventory")) {
                for (ItemStack stack : mc.thePlayer.inventory.mainInventory) add(job, stack, "", 0);
            } else add(job, mc.thePlayer.getHeldItem(), "", 0);
            if (job.items.isEmpty()) throw new IllegalArgumentException("Нет предметов для экспорта");
            Files.createDirectories(job.directory.resolve("icons"));
            pending = job;
            sender.addChatMessage(
                new ChatComponentText(
                    "Экспортирую реальные подсказки и иконки: " + job.items.size()
                        + " предметов. Это локальная копия для проверки на сайте."));
        } catch (Exception e) {
            throw new WrongUsageException("Экспорт не начат: " + e.getMessage());
        }
    }

    private static void add(Job job, ItemStack original, String parent, int depth) throws Exception {
        if (original == null) return;
        if (depth > 8 || job.items.size() >= 1024)
            throw new IllegalArgumentException("Слишком большой или вложенный набор");
        NbtAuditLimit.check(original.getTagCompound());
        ItemStack stack = original.copy();
        String registry = Item.itemRegistry.getNameForObject(stack.getItem());
        // Kit wrappers are expanded: the store should show the items that the player actually receives.
        if (("avaritiaddons:CompressedChest".equalsIgnoreCase(registry)
            || "avaritiaddons:InfinityChest".equalsIgnoreCase(registry)) && stack.hasTagCompound()
            && stack.getTagCompound()
                .hasKey("Contents", 9)) {
            NBTTagList list = stack.getTagCompound()
                .getTagList("Contents", 10);
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound tag = list.getCompoundTagAt(i);
                ItemStack child = ItemStack.loadItemStackFromNBT(tag);
                if (child == null) throw new IllegalArgumentException("Неизвестный предмет внутри сундука");
                if (tag.hasKey("intCount", 3)) child.stackSize = tag.getInteger("intCount");
                add(job, child, parent + "/" + registry + ":" + tag.getInteger("Slot"), depth + 1);
            }
        } else {
            if (stack.stackSize <= 0) throw new IllegalArgumentException("Некорректное количество предметов");
            job.items.add(stack);
            job.parents.add(parent);
        }
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        Job job = pending;
        try {
            if (mc.thePlayer == null) throw new IllegalStateException("Соединение закрыто");
            int slot = job.manifest.size();
            ItemStack stack = job.items.get(slot);
            JsonObject item = new JsonObject();
            item.addProperty("slot", slot);
            item.addProperty("semantic_key", "slot-" + slot);
            item.addProperty("registry_id", Item.itemRegistry.getNameForObject(stack.getItem()));
            cpw.mods.fml.common.registry.GameRegistry.UniqueIdentifier identifier = cpw.mods.fml.common.registry.GameRegistry
                .findUniqueIdentifierFor(stack.getItem());
            cpw.mods.fml.common.ModContainer mod = identifier == null ? null
                : cpw.mods.fml.common.Loader.instance()
                    .getIndexedModList()
                    .get(identifier.modId);
            if (mod != null) item.addProperty("mod_name", mod.getName());
            item.addProperty("display_name", stack.getDisplayName());
            item.addProperty("quantity", stack.stackSize);
            item.addProperty(
                "rarity",
                stack.getRarity()
                    .name()
                    .toLowerCase(java.util.Locale.ROOT));
            JsonArray tooltip = new JsonArray();
            for (Object line : stack.getTooltip(mc.thePlayer, true))
                tooltip.add(new com.google.gson.JsonPrimitive(String.valueOf(line)));
            item.add("tooltip", tooltip);
            JsonObject metadata = new JsonObject();
            metadata.addProperty("damage", stack.getItemDamage());
            metadata.addProperty("container_path", job.parents.get(slot));
            metadata.addProperty("language", mc.gameSettings.language);
            item.add("metadata", metadata);
            JsonObject icon = new JsonObject();
            byte[] png = icon(mc, stack);
            String hash = hex(
                MessageDigest.getInstance("SHA-256")
                    .digest(png));
            Files.write(job.directory.resolve("icons/" + hash + ".png"), png);
            icon.addProperty("type", "content_hash");
            icon.addProperty("reference", hash);
            item.add("icon", icon);
            job.manifest.add(item);
            if (job.manifest.size() == job.items.size()) {
                JsonObject root = new JsonObject();
                root.addProperty("delivery_key", job.id);
                root.addProperty("executor_version", CointCore.VERSION);
                root.addProperty("language", mc.gameSettings.language);
                root.add("items", job.manifest);
                Files.write(
                    job.directory.resolve("manifest-draft.json"),
                    new GsonBuilder().setPrettyPrinting()
                        .disableHtmlEscaping()
                        .create()
                        .toJson(root)
                        .getBytes(StandardCharsets.UTF_8));
                pending = null;
                mc.thePlayer.addChatMessage(
                    new ChatComponentText(
                        "Готово: " + job.directory.toAbsolutePath()
                            + ". Данные ещё нужно проверить и импортировать на сайте."));
            }
        } catch (Exception | LinkageError e) {
            pending = null;
            CointCore.LOG.error("[KitManifest] Export failed; incomplete output is not importable", e);
            if (mc.thePlayer != null)
                mc.thePlayer.addChatMessage(new ChatComponentText("Экспорт не завершён: " + e.getMessage()));
        }
    }

    private static byte[] icon(Minecraft mc, ItemStack original) throws Exception {
        if (!OpenGlHelper.isFramebufferEnabled()) throw new IllegalStateException("Для иконок нужен framebuffer");
        int previousFramebuffer = GL11.glGetInteger(0x8CA6); // FRAMEBUFFER_BINDING, also defined by
                                                             // EXT_framebuffer_object.
        int previousTextureUnit = GL11.glGetInteger(0x84E0); // ACTIVE_TEXTURE
        Framebuffer buffer = null;
        int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            buffer = new Framebuffer(32, 32, true);
            buffer.bindFramebuffer(true);
            GL11.glClearColor(0, 0, 0, 0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, 16, 16, 0, -1000, 1000);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            RenderHelper.enableGUIStandardItemLighting();
            ItemStack rendered = original.copy();
            rendered.stackSize = 1;
            new RenderItem().renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), rendered, 0, 0);
            ByteBuffer pixels = BufferUtils.createByteBuffer(32 * 32 * 4);
            GL11.glReadPixels(0, 0, 32, 32, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
                int offset = (y * 32 + x) * 4;
                int rgba = (pixels.get(offset + 3) & 255) << 24 | (pixels.get(offset) & 255) << 16
                    | (pixels.get(offset + 1) & 255) << 8
                    | pixels.get(offset + 2) & 255;
                image.setRGB(x, 31 - y, rgba);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(matrixMode);
            if (buffer != null) buffer.deleteFramebuffer();
            OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, previousFramebuffer);
            OpenGlHelper.setActiveTexture(previousTextureUnit);
            GL11.glPopAttrib();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format("%02x", b & 255));
        return text.toString();
    }

    private static final class Job {

        String id;
        Path directory;
        final List<ItemStack> items = new ArrayList<>();
        final List<String> parents = new ArrayList<>();
        final JsonArray manifest = new JsonArray();
    }
}
