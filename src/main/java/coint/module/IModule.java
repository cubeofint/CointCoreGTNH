package coint.module;

/**
 * Interface for mod modules. Each module represents a distinct feature
 * that can be enabled/disabled independently.
 */
public interface IModule {

    /**
     * @return Unique identifier for this module
     */
    String getId();

    /**
     * @return Human-readable name of the module
     */
    String getName();

    /**
     * Called during FML preInit phase
     */
    void preInit();

    /**
     * Called during FML init phase
     */
    void init();

    /**
     * Called during FML postInit phase
     */
    void postInit();

    /**
     * Called when server is starting (for registering commands, etc.)
     */
    default void serverStarting() {}

    /**
     * @return Whether this module is enabled
     */
    boolean isEnabled();
}
