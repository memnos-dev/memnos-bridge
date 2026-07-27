package dev.memnos.controlbridge;

import net.citizensnpcs.api.ai.event.NavigationBeginEvent;
import net.citizensnpcs.api.ai.event.NavigationCancelEvent;
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent;
import net.citizensnpcs.api.ai.event.NavigationStuckEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/** Diagnostics: logs Citizens navigation lifecycle events when
 *  debug-wire-logging is enabled. Coords never logged (house rule). */
public final class NavDebugListener implements Listener {

    private final Plugin plugin;
    private final BridgeConfig config;

    public NavDebugListener(Plugin plugin, BridgeConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @EventHandler
    public void onBegin(NavigationBeginEvent e) {
        if (!config.debugWireLogging()) return;
        plugin.getLogger().info("NAV BEGIN npc=" + e.getNPC().getId());
    }

    @EventHandler
    public void onCancel(NavigationCancelEvent e) {
        if (!config.debugWireLogging()) return;
        plugin.getLogger().info("NAV CANCEL npc=" + e.getNPC().getId()
                + " reason=" + e.getCancelReason());
    }

    @EventHandler
    public void onComplete(NavigationCompleteEvent e) {
        if (!config.debugWireLogging()) return;
        plugin.getLogger().info("NAV COMPLETE npc=" + e.getNPC().getId());
    }

    @EventHandler
    public void onStuck(NavigationStuckEvent e) {
        if (!config.debugWireLogging()) return;
        plugin.getLogger().info("NAV STUCK npc=" + e.getNPC().getId());
    }
}