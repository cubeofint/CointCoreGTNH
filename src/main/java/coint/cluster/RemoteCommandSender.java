package coint.cluster;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

/**
 * An admin of another server running a command here for a player who plays here. Its rights were checked on his own
 * server; what the command answers is kept to be sent back to him.
 */
final class RemoteCommandSender implements ICommandSender {

    private final String name;
    final List<IChatComponent> replies = new ArrayList<>();

    RemoteCommandSender(String name) {
        this.name = name;
    }

    @Override
    public String getCommandSenderName() {
        return name;
    }

    @Override
    public IChatComponent func_145748_c_() {
        return new ChatComponentText(name);
    }

    @Override
    public void addChatMessage(IChatComponent message) {
        if (replies.size() < 20) {
            replies.add(message);
        }
    }

    @Override
    public boolean canCommandSenderUseCommand(int level, String command) {
        return true;
    }

    @Override
    public ChunkCoordinates getPlayerCoordinates() {
        return new ChunkCoordinates(0, 0, 0);
    }

    @Override
    public World getEntityWorld() {
        return MinecraftServer.getServer()
            .worldServerForDimension(0);
    }
}
