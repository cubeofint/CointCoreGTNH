package coint.module.epochsync;

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

import betterquesting.api.utils.UuidConverter;
import coint.CointCore;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;

public class EpochRegistry {

    public static EpochRegistry INST;

    private static final String EPOCHS_FILE = "cointcore/server/epochs.json";

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

        try {
            if (!epochsFile.exists() && !epochsFile.createNewFile()) {
                return;
            }

            FileReader reader = new FileReader(epochsFile);
            Type type = new TypeToken<List<EpochEntry>>() {}.getType();
            List<EpochEntry> rowEpochs = gson.fromJson(reader, type);

            rowEpochs.stream()
                .sorted(Comparator.comparingInt(EpochEntry::getPriority))
                .forEachOrdered(entry -> { epochs.put(UuidConverter.decodeUuid(entry.questId), entry); });

            CointCore.LOG.info("epochs loaded from config");
        } catch (IOException e) {
            CointCore.LOG.error(e);
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
