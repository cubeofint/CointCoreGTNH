package coint.cluster;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

import serverutils.command.CmdNickFor;
import serverutils.command.tp.CmdTplast;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;

/** Server-thread snapshots. Tab never queues or waits for a database query. */
public final class ClusterCompletion {

    private static final Deque<String> inputs = new ArrayDeque<>();
    private static List<String> offlineNames = Collections.emptyList();
    private static List<String> localNames = Collections.emptyList();
    private static long nextRefresh;
    private static boolean refreshing;
    private static int generation;

    private ClusterCompletion() {}

    public static void begin(String input) {
        inputs.push(input == null ? "" : input);
    }

    public static void end() {
        inputs.pop();
    }

    static void reset() {
        generation++;
        offlineNames = localNames = Collections.emptyList();
        refreshing = false;
        nextRefresh = 0;
    }

    static void tick(long now) {
        if (!ClusterService.isRunning() || refreshing || now < nextRefresh) return;
        nextRefresh = now + 30_000L;
        List<String> local = new ArrayList<>();
        if (Universe.loaded()) {
            for (ForgePlayer player : Universe.get()
                .getPlayers()) {
                if (!player.isFake()) local.add(player.getName());
            }
        }
        localNames = local;
        refreshing = true;
        int requestedGeneration = generation;
        if (!ClusterService.query(ClusterDatabase::completionNames, names -> {
            if (generation == requestedGeneration) {
                offlineNames = names;
                refreshing = false;
            }
        }, error -> { if (generation == requestedGeneration) refreshing = false; })) refreshing = false;
    }

    private static ICommand command(String input) {
        if (!input.startsWith("/")) return null;
        int space = input.indexOf(' ');
        return space < 0 ? null
            : (ICommand) MinecraftServer.getServer()
                .getCommandManager()
                .getCommands()
                .get(input.substring(1, space));
    }

    public static String[] names(String[] local) {
        if (inputs.isEmpty() || !ClusterService.isRunning()) return null;
        Map<String, String> names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        add(names, java.util.Arrays.asList(local));
        add(
            names,
            ClusterService.remoteTab()
                .keySet());
        if (command(inputs.peek()) instanceof CmdTplast) {
            add(names, localNames);
            add(names, offlineNames);
        }
        return names.values()
            .toArray(new String[0]);
    }

    /** Covers commands such as SU nickfor and Coint heal that have no username completion of their own. */
    public static List<String> complete(ICommandSender sender, String input, List<String> original) {
        if (!ClusterService.isRunning()) return original;
        ICommand command = command(input);
        if (command == null || !command.canCommandSenderUseCommand(sender)) return original;
        String[] args = input.substring(input.indexOf(' ') + 1)
            .split(" ", -1);
        int index = args.length - 1;
        boolean username = command.isUsernameIndex(args, index)
            || (index == 0 && (command instanceof CmdNickFor || command instanceof coint.commands.CommandHeal));
        if (!username || (command instanceof CmdTplast && args.length > 2)) return original;
        Map<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (original != null) add(result, original);
        String[] names = names(
            MinecraftServer.getServer()
                .getConfigurationManager()
                .getAllUsernames());
        if (names != null) {
            String prefix = args[index];
            for (String name : names) {
                if (name.regionMatches(true, 0, prefix, 0, prefix.length())) result.put(name, name);
            }
        }
        return new ArrayList<>(result.values());
    }

    private static void add(Map<String, String> names, Iterable<String> values) {
        for (String name : values) {
            if (name != null && !name.isEmpty()) names.putIfAbsent(name, name);
        }
    }
}
