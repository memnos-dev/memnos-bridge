package dev.memnos.controlbridge;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.astar.pathfinder.DoorExaminer;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer;

import java.util.*;

/**
 * Owns every Citizens call (ADR-002 E1 encapsulation boundary). All methods run
 * on the server main thread (the dispatcher and listener hop to it before
 * calling in), so plain HashMaps are safe.
 */
public final class NpcManager {

    /** Nearest spawned NPC to a player, with its distance in blocks. */
    public record NearestNpc(String npcId, double distance) {
    }

    private final Plugin plugin;
    private final double radius;
    private final boolean debugWireLogging;

    // npc_id -> Citizens NPC. Rebuilt from the registry on enable.
    private final Map<String, NPC> index = new HashMap<>();
    // npc_id -> last player who spoke to it (resolves the "direct" audience).
    private final Map<String, UUID> lastInteractor = new HashMap<>();
    // (npcId|playerUuid) currently inside radius — for outside→inside edge detection.
    private final Set<String> inside = new HashSet<>();
    // Navigation settings, delivered by the core via the configure command
    // after each handshake. Compiled fallbacks cover the window before the
    // first configure arrives (NPCs are indexed BEFORE connect — the index
    // gates the connection) and a core that never sends one.
    // FALLBACK COUPLING: 160 must stay >= the core's default
    // BEHAVIOR_PLACE_THRESHOLD_BLOCKS (150); the authoritative value is
    // core-derived and re-applied on every configure.
    private double navRange = 160.0;
    private int navStuckRetries = 2;

    // ADR-045 E2: compiled fallback mirrors the core default. Reporting exists
// only when the core explicitly requests mode "core" via configure.
    private String navAuthority = "plugin";

    /** One controller-driven navigation being tracked (mode "core" only). */
    record ActiveNav(String navId, String worldId, java.util.List<double[]> points) {}
    private final Map<String, ActiveNav> activeNavs = new HashMap<>();
    private static final int MAX_TRAJECTORY_POINTS = 1800; // 30 min @ 1 Hz

    private BridgeClient client; // attached by the plugin after both exist
    public void attachClient(BridgeClient client) { this.client = client; }
    public boolean isCoreAuthority() { return "core".equals(navAuthority); }

    public record Approach(UUID playerUuid, String npcId, double distance) {}

    public NpcManager(Plugin plugin, double radius, boolean debugWireLogging) {
        this.plugin = plugin;
        this.radius = radius;
        this.debugWireLogging = debugWireLogging;
    }

    /** Apply core-delivered navigation settings and re-apply the defaults to
     *  every indexed NPC. Called on every configure (each (re)connect) —
     *  idempotent. Main-thread only, like every other method here. */
    public void applyNavConfig(double range, int stuckRetries, String authority) {
        this.navRange = range;
        this.navStuckRetries = stuckRetries;
        if (!authority.equals(this.navAuthority)) {
            // Mode switch mid-flight: buffers from the old mode are meaningless.
            activeNavs.clear();
        }
        this.navAuthority = authority;
        for (NPC npc : index.values()) {
            applyNavigationDefaults(npc);
        }
        plugin.getLogger().info("Nav config applied to " + index.size() + " NPC(s).");
    }

    /** Players who just crossed into an NPC's radius this scan. Pure-ish: mutates `inside`. */
    public List<Approach> scanApproaches() {
        List<Approach> crossed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, NPC> e : index.entrySet()) {
            String npcId = e.getKey();
            NPC npc = e.getValue();
            if (npc.getEntity() == null) continue;
            Location nloc = npc.getEntity().getLocation();
            if (nloc.getWorld() == null) continue;
            for (Player p : nloc.getWorld().getPlayers()) {
                double d = p.getLocation().distance(nloc);
                if (d > radius) continue;
                String key = npcId + "|" + p.getUniqueId();
                seen.add(key);
                if (inside.add(key)) {                 // add() == true → was outside
                    crossed.add(new Approach(p.getUniqueId(), npcId, d));
                }
            }
        }
        inside.retainAll(seen);                        // left radius / logged out → reset
        return crossed;
    }

    /** Rebuild the id<->NPC index by scanning Citizens for the identity trait. */
    public void rebuildIndex() {
        index.clear();
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            IdentityTrait trait = npc.getTraitNullable(IdentityTrait.class);
            if (trait != null && trait.getNpcId() != null && !trait.getNpcId().isBlank()) {
                applyNavigationDefaults(npc);
                index.put(trait.getNpcId(), npc);
            }
        }
    }

    /** Spawn at the controller-supplied position. Idempotent (wire is at-least-once). */
    public void spawn(String npcId, String name, double x, double y, double z,
                      String worldId, String skinRef) {
        if (index.containsKey(npcId)) {
            return; // already present; ignore duplicate spawn
        }
        World world = resolveWorld(worldId);
        if (world == null) {
            plugin.getLogger().warning("Spawn skipped for " + npcId + ": world not found.");
            return;
        }
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, name);
        npc.getOrAddTrait(IdentityTrait.class).setNpcId(npcId);
        if (skinRef != null && !skinRef.isBlank()) {
            npc.getOrAddTrait(SkinTrait.class).setSkinName(skinRef);
        }
        npc.spawn(new Location(world, x, y, z)); // coordinates are never logged
        applyNavigationDefaults(npc);
        index.put(npcId, npc);
        plugin.getLogger().info("Spawned NPC " + npcId);
    }

    /** Navigation defaults, applied to every NPC under bridge control.
     *  Deterministic override of whatever Citizens config or manual /npc pathopt
     *  left behind — tenant-side Citizens configs are not under our control.
     *  - range: core-delivered via configure (derived from the place threshold);
     *    compiled fallback until the first configure arrives.
     *  - useNewPathfinder(true): the Citizens A* pathfinder (pathfinder-type
     *    CITIZENS) — the MINECRAFT navigator does not open doors reliably.
     *  - stationaryTicks(60): explicit stuck detection (3s without progress),
     *    independent of the tenant's Citizens config defaults.
     *  - distanceMargin(2.0): arrival tolerance; prevents endless micro-
     *    adjustment jitter at the target.
     *  - DoorExaminer: walk through wooden doors/gates, opening them (iron
     *    doors stay impassable by design — redstone territory). Idempotent:
     *    examiners are a list and this method runs again on every
     *    rebuildIndex AND every configure — without the guard each pass
     *    appends a duplicate. */
    private void applyNavigationDefaults(NPC npc) {
        NavigatorParameters params = npc.getNavigator().getDefaultParameters();
        params.range((float) navRange);
        params.useNewPathfinder(true);
        params.stationaryTicks(60);
        params.distanceMargin(2.0);
        boolean hasDoorExaminer = false;
        for (BlockExaminer examiner : params.examiners()) {
            if (examiner instanceof DoorExaminer) {
                hasDoorExaminer = true;
                break;
            }
        }
        if (!hasDoorExaminer) {
            params.examiner(new DoorExaminer());
        }
    }

    /** Despawn and forget. Idempotent. */
    public void despawn(String npcId) {
        NPC npc = index.remove(npcId);
        lastInteractor.remove(npcId);
        if (npc != null) {
            npc.destroy();
            plugin.getLogger().info("Despawned NPC " + npcId);
        }
    }

    /** Nearest spawned NPC within radius, same world. Empty = proximity filter drops the event. */
    public Optional<NearestNpc> findNearest(Player player) {
        Location ploc = player.getLocation();
        String bestId = null;
        double best = Double.MAX_VALUE;
        for (Map.Entry<String, NPC> entry : index.entrySet()) {
            NPC npc = entry.getValue();
            if (debugWireLogging) {
                plugin.getLogger().info("findNearest: spawned=" + npc.isSpawned()
                        + " entity=" + (npc.getEntity() == null ? "null" : "present"));
            }
            if (!npc.isSpawned() || npc.getEntity() == null) {
                continue; // not fully materialised yet (Citizens async lifecycle)
            }
            Location nloc = npc.getEntity().getLocation();
            if (nloc.getWorld() == null || !nloc.getWorld().equals(ploc.getWorld())) {
                continue;
            }
            double d = nloc.distance(ploc);
            if (debugWireLogging) {
                plugin.getLogger().info("findNearest: dist=" + d + " radius=" + radius);
            }
            if (d <= radius && d < best) {
                best = d;
                bestId = entry.getKey();
            }
        }
        return bestId == null ? Optional.empty() : Optional.of(new NearestNpc(bestId, best));
    }

    public void recordInteractor(String npcId, UUID playerUuid) {
        lastInteractor.put(npcId, playerUuid);
    }

    /** Resolve the audience for npc_say / npc_thinking from the audience scope. */
    public List<Player> resolveAudience(String npcId, String audience) {
        switch (audience) {
            case "broadcast":
                return new ArrayList<>(Bukkit.getOnlinePlayers());
            case "direct": {
                UUID uuid = lastInteractor.get(npcId);
                Player p = uuid == null ? null : Bukkit.getPlayer(uuid);
                return p == null ? List.of() : List.of(p);
            }
            case "nearby": {
                NPC npc = index.get(npcId);
                if (npc == null || !npc.isSpawned() || npc.getEntity().getLocation().getWorld() == null) {
                    return List.of();
                }
                Location loc = npc.getEntity().getLocation();
                List<Player> out = new ArrayList<>();
                for (Player p : loc.getWorld().getPlayers()) {
                    if (p.getLocation().distance(loc) <= radius) {
                        out.add(p);
                    }
                }
                return out;
            }
            default:
                return List.of();
        }
    }

    /** Display name for rendering; falls back if the NPC is unknown. */
    public String displayName(String npcId) {
        NPC npc = index.get(npcId);
        return npc != null ? npc.getName() : "NPC";
    }

    private World resolveWorld(String worldId) {
        try {
            World w = Bukkit.getWorld(UUID.fromString(worldId));
            if (w != null) {
                return w;
            }
        } catch (IllegalArgumentException ignored) {
            // not a UUID -> fall through to name lookup (eases manual testing)
        }
        return Bukkit.getWorld(worldId);
    }

    /** Move an NPC toward a target via Citizens pathfinding. The plugin owns
     *  execution robustness (retry-then-teleport stuck escalation); the core
     *  only decided WHAT (move vs place, threshold-gated core-side). */
    public void move(String npcId, String navId, double x, double y, double z, String worldId) {
        NPC npc = index.get(npcId);
        if (npc == null || !npc.isSpawned() || npc.getEntity() == null) {
            plugin.getLogger().warning("Move skipped for " + npcId + ": not present.");
            return;
        }
        World world = resolveWorld(worldId);
        if (world == null) {
            plugin.getLogger().warning("Move skipped for " + npcId + ": world not found.");
            return;
        }
        if (isCoreAuthority()) {
            // Overwrite-first: a new move supersedes any tracked nav for this NPC.
            // The superseded buffer is discarded, never sent (ADR-045 E3).
            Location start = npc.getEntity().getLocation();
            java.util.List<double[]> pts = new java.util.ArrayList<>();
            pts.add(new double[]{start.getX(), start.getY(), start.getZ()});
            activeNavs.put(npcId, new ActiveNav(navId, start.getWorld().getUID().toString(), pts));
        } else {
            activeNavs.remove(npcId);
        }
        npc.getNavigator().setTarget(new Location(world, x, y, z)); // coords never logged
        // Per-navigation stuck escalation: fresh instance so the retry counter
        // never leaks across navigations. Local parameters are this
        // navigation's copy of the defaults.
        npc.getNavigator().getLocalParameters()
                .stuckAction(new RetryThenTeleportStuckAction(plugin, npcId, navStuckRetries, this, client));
        plugin.getLogger().info("Move dispatched for " + npcId);
    }

    /** Materialize an NPC at a target position (catch-up placement).
     *  Counterpart to move(): teleport loads target chunks and bypasses the
     *  navigator's give-up on unreachable targets. A despawned NPC is spawned
     *  directly at the target instead. */
    public void place(String npcId, double x, double y, double z, String worldId) {
        NPC npc = index.get(npcId);
        if (npc == null) {
            plugin.getLogger().warning("Place skipped for " + npcId + ": unknown NPC.");
            return;
        }
        World world = resolveWorld(worldId);
        if (world == null) {
            plugin.getLogger().warning("Place skipped for " + npcId + ": world not found.");
            return;
        }
        Location target = new Location(world, x, y, z); // coords never logged
        if (!npc.isSpawned()) {
            npc.spawn(target);
        } else {
            npc.getNavigator().cancelNavigation();
            npc.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        plugin.getLogger().info("Place dispatched for " + npcId);
    }

    /**
     * Cheap situational snapshot for one NPC (CC-WO-03): canonical time-of-day +
     * players within the proximity radius, same world. Empty if the NPC is absent
     * (unknown id or not yet materialised). Owns the Citizens/Bukkit reads (ADR-002 E1).
     * Proximity filter mirrors resolveAudience("nearby").
     */
    public Optional<WorldQueryResult> observe(String npcId) {
        NPC npc = index.get(npcId);
        if (npc == null || !npc.isSpawned() || npc.getEntity() == null) {
            return Optional.empty();
        }
        Location loc = npc.getEntity().getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return Optional.empty();
        }
        int minuteOfDay = WorldQueryResult.minuteOfDay(world.getTime());
        List<WorldQueryResult.NearbyPlayer> nearby = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            double d = p.getLocation().distance(loc);
            if (d <= radius) {
                Location pl = p.getLocation();
                nearby.add(new WorldQueryResult.NearbyPlayer(
                        p.getUniqueId().toString(), d, pl.getX(), pl.getY(), pl.getZ()));
            }
        }
        return Optional.of(new WorldQueryResult(minuteOfDay, nearby,
                loc.getX(), loc.getY(), loc.getZ()));
    }

    /** Set the Citizens nameplate of an existing NPC in place, without
     *  respawn. Idempotent. Unknown npc_id: log-and-ignore -- the next
     *  connects reconcile heals via spawn_npc, which carries the name
     *  */
    public void renameNpc(String npcId, String name) {
        NPC npc = index.get(npcId);
        if (npc == null) {
            plugin.getLogger().warning("Rename skipped for " + npcId + ": unknown NPC.");
            return;
        }
        npc.setName(name);
        plugin.getLogger().info("Renamed NPC " + npcId + " -> '" + name + "'");
    }

    /** Number of indexed NPCs; used for restart-visibility logging. */
    public int indexSize() {
        return index.size();
    }

    /** Immutable snapshot of indexed npc_ids for the connect report.
     *  Main-thread only, like every other method here. */
    public Set<String> indexedNpcIds() {
        return Set.copyOf(index.keySet());
    }

    /** 1 Hz, main thread. Appends one sample per tracked nav. Coords are never logged. */
    public void sampleActiveNavs() {
        if (!isCoreAuthority() || activeNavs.isEmpty()) return;
        var it = activeNavs.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            NPC npc = index.get(e.getKey());
            if (npc == null || !npc.isSpawned() || npc.getEntity() == null) {
                it.remove();
                continue;
            }
            Location loc = npc.getEntity().getLocation();
            e.getValue().points().add(new double[]{loc.getX(), loc.getY(), loc.getZ()});
            if (e.getValue().points().size() > MAX_TRAJECTORY_POINTS) {
                plugin.getLogger().warning("NAV trajectory buffer overflow; discarding npc=" + e.getKey());
                it.remove(); // arrival will then send nothing — treated as never-terminated
            }
        }
    }

    public java.util.Optional<ActiveNav> takeNav(String npcId) {
        return java.util.Optional.ofNullable(activeNavs.remove(npcId));
    }

    /** Reverse lookup Citizens-NPC -> memnos npc_id (index is tiny; linear is fine). */
    public java.util.Optional<String> npcIdFor(NPC npc) {
        for (var e : index.entrySet()) {
            if (e.getValue().getId() == npc.getId()) return java.util.Optional.of(e.getKey());
        }
        return java.util.Optional.empty();
    }
}