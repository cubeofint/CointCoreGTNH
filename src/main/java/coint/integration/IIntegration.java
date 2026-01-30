package coint.integration;

/**
 * Interface for optional mod integrations. Integrations are only loaded
 * if the target mod is present.
 */
public interface IIntegration {

    /**
     * @return The mod ID of the target mod for this integration
     */
    String getModId();

    /**
     * @return Whether the target mod is loaded and available
     */
    boolean isAvailable();

    /**
     * Called to register this integration when the target mod is available.
     * This is called during postInit phase.
     */
    void register();

    /**
     * @return Human-readable name of this integration
     */
    default String getName() {
        return getModId() + " Integration";
    }
}
