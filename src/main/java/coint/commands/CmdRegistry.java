package coint.commands;

import coint.CointCore;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CmdRegistry {

    public static void register(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandCards());
        event.registerServerCommand(new CommandSync());
        event.registerServerCommand(new CommandRepair());
        event.registerServerCommand(
            new coint.commands.privilege.CommandPrivilege(coint.commands.privilege.CommandPrivilege.Kind.CHARGE));
        event.registerServerCommand(
            new coint.commands.privilege.CommandPrivilege(coint.commands.privilege.CommandPrivilege.Kind.MAXCHARGE));
        event.registerServerCommand(
            new coint.commands.privilege.CommandPrivilege(coint.commands.privilege.CommandPrivilege.Kind.EXPADD));
        event.registerServerCommand(new CommandHeal());
        event.registerServerCommand(new CommandFeed());
        event.registerServerCommand(new CommandKit());
        event.registerServerCommand(new CommandNightVision());
        event.registerServerCommand(new CommandTpAlias());
        event.registerServerCommand(new CommandWarn());
        event.registerServerCommand(new CommandMute());
        event.registerServerCommand(new CommandUnmute());
        event.registerServerCommand(new CommandTBan());
        event.registerServerCommand(new CommandTRank());
        event.registerServerCommand(new CommandReload());
        event.registerServerCommand(new CommandReply());
        event.registerServerCommand(new CommandSpy());
        event.registerServerCommand(new CommandCleanup());
        event.registerServerCommand(new CommandHub());
        event.registerServerCommand(new CommandClass());
        event.registerServerCommand(new CommandIgnore());
        event.registerServerCommand(new CommandUnloadDimensions());
        event.registerServerCommand(new CommandAnchors());
        event.registerServerCommand(new CommandBatchModeAll());
        event.registerServerCommand(new CommandWorlds());
        event.registerServerCommand(new CommandWorldsAdmin());
        event.registerServerCommand(new CommandPDim());
        event.registerServerCommand(new CommandPlayerReset());
        event.registerServerCommand(new CommandRestart());
        event.registerServerCommand(new CommandMigrate());
        event.registerServerCommand(new CommandMigrationConfirm());
        event.registerServerCommand(new coint.cluster.CommandServer());
        event.registerServerCommand(new coint.cluster.CommandCluster());
        event.registerServerCommand(new coint.blocklog.CommandBlockLog());
        event.registerServerCommand(new coint.chunkbonus.CommandChunkBonus());
        event.registerServerCommand(new coint.cluster.CommandSeen());
        event.registerServerCommand(new coint.trade.CommandTrade());
        CointCore.LOG.debug("Registered server commands");
    }
}
