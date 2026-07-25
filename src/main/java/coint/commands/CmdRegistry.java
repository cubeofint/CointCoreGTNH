package coint.commands;

import coint.CointCore;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CmdRegistry {

    public static void register(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandSync());
        event.registerServerCommand(new CommandRepair());
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
        CointCore.LOG.debug("Registered server commands");
    }
}
