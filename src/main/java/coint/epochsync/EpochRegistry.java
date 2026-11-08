package coint.epochsync;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.libraries.com.google.common.reflect.TypeToken;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import betterquesting.api.utils.UuidConverter;
import coint.CointCore;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;

public class EpochRegistry {

    public static EpochRegistry INST;

    private static final String EPOCHS_FILE = "cointcore/epochs.json";

    public HashMap<UUID, EpochEntry> epochs;

    private MinecraftServer server;
    private Gson gson;
    private File epochsFile;

    private EpochRegistry(MinecraftServer s) {
        server = s;
        epochs = new LinkedHashMap<>();
        gson = new GsonBuilder().create();
        epochsFile = null;
    }

    public static void init(FMLServerAboutToStartEvent event) {
        INST = new EpochRegistry(event.getServer());
        INST.reload();
    }

    public void reload() {
        epochsFile = server.getFile(EPOCHS_FILE);
        epochs.clear();

        File dir = epochsFile.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            CointCore.LOG.error("Can't create {} for the epochs config", dir);
            return;
        }
        try {
            if (epochsFile.createNewFile()) {
                CointCore.LOG.info("empty epochs config created");
            }

            Type type = new TypeToken<List<EpochEntry>>() {}.getType();
            List<EpochEntry> rowEpochs;
            try (FileReader reader = new FileReader(epochsFile)) {
                rowEpochs = gson.fromJson(reader, type);
            }
            // An empty file (a new world, a new server) reads as null: no epochs.
            if (rowEpochs == null) {
                CointCore.LOG.info("epochs config is empty: no epochs");
                return;
            }

            rowEpochs.stream()
                .sorted(Comparator.comparingInt(EpochEntry::getPriority))
                .forEachOrdered(entry -> { epochs.put(UuidConverter.decodeUuid(entry.questId), entry); });

            CointCore.LOG.info("epochs loaded from config");
        } catch (IOException | JsonParseException e) {
            // A broken config must not stop the server from starting: no epochs until it is fixed.
            CointCore.LOG.error("Can't read the epochs config {}", epochsFile, e);
        }
    }

    // TODO: add default config
    private void saveDefaultEpochs() {
        // epochsFile = server.getFile(EPOCHS_FILE);
    }

    public boolean isEpoch(String rank) {
        boolean result = false;
        for (EpochEntry e : epochs.values()) {
            result = result || (e.rankName.equals(rank));
        }
        return result;
    }

    public EpochEntry getEpoch(UUID quest) {
        return epochs.get(quest);
    }

    public EpochEntry getEpoch(String rank) {
        for (EpochEntry e : epochs.values()) {
            if (e.rankName.equals(rank)) return e;
        }
        return null;
    }
}
