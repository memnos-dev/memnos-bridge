package dev.memnos.controlbridge;

import org.bukkit.configuration.file.FileConfiguration;

/** Immutable view of the operator-supplied plugin configuration. */
public final class BridgeConfig {

    private final String controllerUrl;
    private final String authToken;
    private final int reconnectDelaySeconds;
    private final double proximityRadius;
    private final boolean debugWireLogging;

    private BridgeConfig(String controllerUrl, String authToken, int reconnectDelaySeconds,
                         double proximityRadius, boolean debugWireLogging) {
        this.controllerUrl = controllerUrl;
        this.authToken = authToken;
        this.reconnectDelaySeconds = reconnectDelaySeconds;
        this.proximityRadius = proximityRadius;
        this.debugWireLogging = debugWireLogging;

    }

    /** Read configuration from the plugin's config.yml. */
    public static BridgeConfig from(FileConfiguration config) {
        String url = config.getString("controller-url", "");
        String token = config.getString("auth-token", "");
        int delay = config.getInt("reconnect-delay-seconds", 5);
        double radius = config.getDouble("proximity-radius", 16.0);
        boolean debugWire = config.getBoolean("debug-wire-logging", false);
        return new BridgeConfig(url, token, delay, radius, debugWire);
    }

    public String controllerUrl() {
        return controllerUrl;
    }

    public String authToken() {
        return authToken;
    }

    public int reconnectDelaySeconds() {
        return reconnectDelaySeconds;
    }

    public double proximityRadius() {
        return proximityRadius;
    }

    public boolean debugWireLogging() {
        return debugWireLogging;
    }

    public boolean hasControllerUrl() {
        return controllerUrl != null && !controllerUrl.isBlank();
    }

}