package coint.supply;

import java.util.List;
import java.util.UUID;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;

import coint.cluster.SupplyLink;

/**
 * Links supply buffers. A blank card on a buffer that is not linked makes it the provider of a new link and remembers
 * the link; the card then makes any unlinked buffer, on any server, a remote of that link.
 */
public class ItemSupplyLinkCard extends Item {

    public ItemSupplyLinkCard() {
        setUnlocalizedName("cointcore.supply_link_card");
        setTextureName("cointcore:supply_link_card");
        setCreativeTab(CreativeTabs.tabMisc);
        setMaxStackSize(1);
    }

    static String linkOf(ItemStack card) {
        return card.hasTagCompound() ? card.getTagCompound()
            .getString("link") : "";
    }

    static void use(ItemStack card, TileSupplyBuffer tile, EntityPlayer player) {
        if (!tile.canEdit(player)) {
            say(player, "§cЭто не ваш буфер.");
            return;
        }
        if (!SupplyLink.enabled()) {
            say(player, "§cСеть серверов выключена: привязка невозможна.");
            return;
        }
        String link = linkOf(card);
        switch (tile.getRole()) {
            case UNLINKED -> {
                if (link.isEmpty()) {
                    String newLink = UUID.randomUUID()
                        .toString();
                    tile.makeProvider(newLink);
                    NBTTagCompound tag = new NBTTagCompound();
                    tag.setString("link", newLink);
                    tag.setString("node", SupplyLink.nodeId());
                    card.setTagCompound(tag);
                    say(player, "§aЭтот буфер теперь Provider. Подключите его к главной ME сети.");
                    say(player, "§7Картой можно привязывать Remote-буферы на любом сервере сети.");
                } else {
                    tile.makeRemote(link);
                    say(player, "§aЭтот буфер теперь Remote. Подавайте предметы снизу: они уйдут в главную ME.");
                }
            }
            case PROVIDER -> {
                NBTTagCompound tag = new NBTTagCompound();
                tag.setString("link", tile.getLink());
                tag.setString("node", SupplyLink.nodeId());
                card.setTagCompound(tag);
                say(player, "§aКарта привязана к этому Provider.");
            }
            case REMOTE -> say(player, "§eБуфер уже привязан как Remote.");
        }
    }

    private static void say(EntityPlayer player, String text) {
        player.addChatMessage(new ChatComponentText(text));
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        String link = linkOf(stack);
        if (link.isEmpty()) {
            lines.add("§7Не привязана: ПКМ по новому буферу у главной ME");
        } else {
            lines.add("§7Связь: §f" + (link.length() > 8 ? link.substring(0, 8) : link));
            lines.add(
                "§7Provider на сервере: §f" + stack.getTagCompound()
                    .getString("node"));
            lines.add("§7ПКМ по новому буферу — сделать его Remote");
        }
    }

    @Override
    public boolean hasEffect(ItemStack stack, int pass) {
        return !linkOf(stack).isEmpty();
    }
}
