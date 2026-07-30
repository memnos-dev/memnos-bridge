package dev.memnos.controlbridge;

import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.StuckAction;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * Stuck escalation for controller-driven moves. Mode "plugin" (default):
 * retry pathfinding a fixed number of times, then teleport to the target
 * (arrival guarantee). Mode "core" (ADR-045 E2): after local retries,
 * escalate to the core via npc_nav_stuck and stop navigating — the core
 * owns the ladder (re-route -> explorer -> npc_place).
 */
final class RetryThenTeleportStuckAction implements StuckAction {

    private final Plugin plugin;
    private final String npcId;
    private final int maxRetries;
    private final NpcManager npcManager;
    private final BridgeClient client;
    private int attempts = 0;

    RetryThenTeleportStuckAction(Plugin plugin, String npcId, int maxRetries,
                                 NpcManager npcManager, BridgeClient client) {
        this.plugin = plugin;
        this.npcId = npcId;
        this.maxRetries = maxRetries;
        this.npcManager = npcManager;
        this.client = client;
    }

    @Override
    public boolean run(NPC npc, Navigator navigator) {
        if (attempts++ < maxRetries) {
            // true = keep navigating; the navigator re-attempts the path.
            plugin.getLogger().info("NAV STUCK retry " + attempts + "/" + maxRetries
                    + " npc=" + npcId);
            return true;
        }
        if (npcManager.isCoreAuthority()) {
            var nav = npcManager.takeNav(npcId);
            if (nav.isPresent() && client != null
                    && npc.getEntity() != null && npc.getEntity().getLocation().getWorld() != null) {
                Location last = npc.getEntity().getLocation(); // coords never logged
                client.send(WireSender.npcNavStuck(
                        nav.get().navId(), npcId,
                        last.getWorld().getUID().toString(),
                        last.getX(), last.getY(), last.getZ(),
                        attempts));
                plugin.getLogger().warning("NAV STUCK exhausted; escalated to core npc=" + npcId);
            } else {
                plugin.getLogger().warning("NAV STUCK in core mode without tracked nav; npc=" + npcId);
            }
            return false; // stop navigating — no teleport in core mode
        }
        Location target = navigator.getTargetAsLocation();
        if (target != null && npc.getEntity() != null) {
            plugin.getLogger().warning("NAV STUCK exhausted; teleporting npc=" + npcId);
            npc.getEntity().teleport(target); // keep YOUR existing teleport line if it differs
        }
        return false;
    }
}