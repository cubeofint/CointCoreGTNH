package coint;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import coint.core.CommonProxy;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(
    modid = CointCore.MOD_ID,
    version = CointCore.VERSION,
    name = CointCore.MOD_NAME,
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*", // Server-side only: client doesn't need this mod
    dependencies = "after:betterquesting;after:serverutilities;after:thaumcraft")
public class CointCore {

    public static final String MOD_ID = "cointcore";
    public static final String VERSION = Tags.VERSION;
    public static final String MOD_NAME = "Coint Core GTNH";

    public static final Logger LOG = LogManager.getLogger(MOD_ID);

    // Server-side only - no client proxy needed
    public static final CommonProxy proxy = new CommonProxy();

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    /**
     * Get the proxy instance
     */
    public static CommonProxy getProxy() {
        return proxy;
    }
}
