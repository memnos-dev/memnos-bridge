package dev.memnos.controlbridge;

import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.StuckAction;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/**
 * Stuck escalation for controller-driven moves: retry pathfinding a fixed
 * number of times, then teleport to the target. Rationale: the move contract
 * toward the core is "get the NPC there" — an NPC frozen mid-path is a worse
 * fiction than a rare teleport, and the core's next world_query then reads a
 * consistent position (tick semantics self-heal, no new wire message needed).
 *
 * One instance per navigation (constructed in NpcManager.move); the attempt
 * counter must never be shared across navigations.
 */
final class RetryThenTeleportStuckAction implements StuckAction {

    private final Plugin plugin;
    private final String npcId;
    private final int maxRetries;
    private int attempts = 0;

    RetryThenTeleportStuckAction(Plugin plugin, String npcId, int maxRetries) {
        this.plugin = plugin;
        this.npcId = npcId;
        this.maxRetries = maxRetries;
    }

    @Override
    public boolean run(NPC npc, Navigator navigator) {
        if (attempts++ < maxRetries) {
            // true = keep navigating; the navigator re-attempts the path.
            plugin.getLogger().info("NAV STUCK retry " + attempts + "/" + maxRetries
                    + " npc=" + npcId);
            return true;
        }
        Location target = navigator.getTargetAsLocation();
        if (target != null) {
            // coords never logged (house rule)
            plugin.getLogger().warning("NAV STUCK exhausted; teleporting npc=" + npcId);
            npc.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        return false; // stop this navigation
    }
}