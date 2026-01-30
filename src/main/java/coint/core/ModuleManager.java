package coint.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Logger;

import coint.CointCore;
import coint.integration.IIntegration;
import coint.module.IModule;
import cpw.mods.fml.common.Loader;

/**
 * Manages all modules and integrations for CointCore.
 */
public class ModuleManager {

    private static final Logger LOG = CointCore.LOG;

    private final Map<String, IModule> modules = new HashMap<>();
    private final List<IIntegration> integrations = new ArrayList<>();

    /**
     * Register a module
     */
    public void registerModule(IModule module) {
        if (modules.containsKey(module.getId())) {
            LOG.warn("Module with id '{}' is already registered, skipping", module.getId());
            return;
        }
        modules.put(module.getId(), module);
        LOG.info("Registered module: {} ({})", module.getName(), module.getId());
    }

    /**
     * Register an integration
     */
    public void registerIntegration(IIntegration integration) {
        integrations.add(integration);
        LOG.debug("Registered integration: {}", integration.getName());
    }

    /**
     * Get a module by ID
     */
    public IModule getModule(String id) {
        return modules.get(id);
    }

    /**
     * Get all registered modules
     */
    public Map<String, IModule> getModules() {
        return Collections.unmodifiableMap(modules);
    }

    /**
     * Called during preInit phase
     */
    public void preInit() {
        for (IModule module : modules.values()) {
            if (module.isEnabled()) {
                try {
                    module.preInit();
                    LOG.debug("PreInit completed for module: {}", module.getId());
                } catch (Exception e) {
                    LOG.error("Error during preInit of module {}: {}", module.getId(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Called during init phase
     */
    public void init() {
        for (IModule module : modules.values()) {
            if (module.isEnabled()) {
                try {
                    module.init();
                    LOG.debug("Init completed for module: {}", module.getId());
                } catch (Exception e) {
                    LOG.error("Error during init of module {}: {}", module.getId(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Called during postInit phase
     */
    public void postInit() {
        // Initialize modules
        for (IModule module : modules.values()) {
            if (module.isEnabled()) {
                try {
                    module.postInit();
                    LOG.debug("PostInit completed for module: {}", module.getId());
                } catch (Exception e) {
                    LOG.error("Error during postInit of module {}: {}", module.getId(), e.getMessage(), e);
                }
            }
        }

        // Initialize integrations
        for (IIntegration integration : integrations) {
            if (isModLoaded(integration.getModId())) {
                try {
                    integration.register();
                    LOG.info("Loaded integration: {}", integration.getName());
                } catch (Exception e) {
                    LOG.error("Error loading integration {}: {}", integration.getName(), e.getMessage(), e);
                }
            } else {
                LOG.debug("Skipping integration {} - mod {} not loaded", integration.getName(), integration.getModId());
            }
        }
    }

    /**
     * Called when server is starting
     */
    public void serverStarting() {
        for (IModule module : modules.values()) {
            if (module.isEnabled()) {
                try {
                    module.serverStarting();
                } catch (Exception e) {
                    LOG.error("Error during serverStarting of module {}: {}", module.getId(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Check if a mod is loaded
     */
    private boolean isModLoaded(String modId) {
        return Loader.isModLoaded(modId);
    }
}
