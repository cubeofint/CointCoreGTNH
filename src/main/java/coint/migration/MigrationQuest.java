package coint.migration;

import net.minecraft.server.MinecraftServer;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import betterquesting.api.events.QuestEvent;
import coint.CointConfig;
import coint.cluster.ClusterService;
import coint.supply.SupplyServerTasks;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;

/** A configured quest requests consent; it never bypasses migration checks. */
@EventBusSubscriber
public final class MigrationQuest {

    private MigrationQuest() {}

    @SideOnly(Side.SERVER)
    @SubscribeEvent
    public static void completed(QuestEvent event) {
        if (event.getType() != QuestEvent.Type.COMPLETED || event.getPlayerID() == null
            || !CointConfig.cluster.migrationEnabled
            || !ClusterService.isRunning()
            || !ClusterService.nodeId()
                .equals(CointConfig.cluster.migrationQuestSourceNode.trim()))
            return;
        String configured = CointConfig.cluster.migrationQuestId.trim();
        if (configured.isEmpty() || event.getQuestIDs()
            .stream()
            .noneMatch(
                id -> id.toString()
                    .equalsIgnoreCase(configured)))
            return;
        java.util.UUID playerId = event.getPlayerID();
        SupplyServerTasks.run(() -> {
            if (!Universe.loaded()) return;
            ForgePlayer player = Universe.get()
                .getPlayer(playerId);
            if (player != null && player.hasTeam() && player.team.isOwner(player)) MigrationConsent
                .request(MinecraftServer.getServer(), player, CointConfig.cluster.migrationQuestTargetNode.trim());
        });
    }
}
