package dev.memnos.controlbridge;

import net.citizensnpcs.api.ai.event.NavigationCancelEvent;
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Reports controller-driven navigation outcomes to the core (ADR-045 E1/E3).
 * Arrival sends the buffered trajectory; any cancellation discards it —
 * partial trajectories never reach the wire.
 */
public final class NavOutcomeListener implements Listener {

    private final NpcManager npcManager;
    private final BridgeClient client;

    public NavOutcomeListener(NpcManager npcManager, BridgeClient client) {
        this.npcManager = npcManager;
        this.client = client;
    }

    @EventHandler
    public void onComplete(NavigationCompleteEvent e) {
        // TRAP: NavigationCancelEvent EXTENDS NavigationCompleteEvent in the
        // Citizens API — without this guard every cancel would be reported as
        // an arrival.
        if (e instanceof NavigationCancelEvent) {
            return;
        }
        if (!npcManager.isCoreAuthority()) {
            return;
        }
        npcManager.npcIdFor(e.getNPC()).ifPresent(npcId ->
                npcManager.takeNav(npcId).ifPresent(nav ->
                        client.send(WireSender.npcNavCompleted(
                                nav.navId(), npcId, nav.worldId(), nav.points()))));
    }

    @EventHandler
    public void onCancel(NavigationCancelEvent e) {
        // Superseded / cancelled / stuck-gave-up: buffer is discarded, never
        // sent (ADR-045 E3). takeNav is removal-first, so a stuck escalation
        // that already took the nav makes this a no-op.
        npcManager.npcIdFor(e.getNPC()).ifPresent(npcManager::takeNav);
    }
}