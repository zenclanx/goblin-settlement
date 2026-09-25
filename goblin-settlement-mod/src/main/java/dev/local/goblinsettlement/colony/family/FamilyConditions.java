package dev.local.goblinsettlement.colony.family;

/** A fresh, real-world assessment supplied by the server coordinator. Unknown facts must be false. */
public record FamilyConditions(boolean housingAvailable, boolean foodAvailable,
                               boolean motherHealthy, boolean fatherHealthy) {
    public boolean permitsConception() {
        return housingAvailable && foodAvailable && motherHealthy && fatherHealthy;
    }

    public boolean permitsBirth() {
        return housingAvailable && foodAvailable && motherHealthy;
    }
}
