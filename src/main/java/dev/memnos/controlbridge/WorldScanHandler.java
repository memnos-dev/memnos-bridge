package dev.memnos.controlbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.citizensnpcs.api.CitizensAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Marker;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.Plugin;
import com.google.gson.JsonElement;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.data.BlockData;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * Handles inbound "world_scan": scans a bounded area of the primary
 * world for author-relevant markers (signs, villagers, display entities, marker
 * entities, item frames, named mobs) and returns them as neutral POI candidates
 * in the PoiCandidateIn wire shape, source "live_scan".
 *
 * Threading: handle() is ALREADY on the main thread — CommandDispatcher hops
 * there before dispatch (same as WorldQueryHandler). So NO runTask here.
 *
 * Chunk loading: getChunkAt() sync-loads unloaded chunks. At
 * DEFAULT_RADIUS 96 that is a 13x13 chunk square around spawn — spawn chunks
 * are typically resident, so the sync cost is acceptable for v0. If large
 * custom bounds ever stutter the main thread, the seam is this loop:
 * switch to world.getChunkAtAsync + candidate accumulation across ticks.
 * Chunk loading: terrain sync via getChunkAt; entities load async,
 * so the handler tickets every chunk and polls isEntitiesLoaded()
 * before scanning (5s loud fallback).
 *
 * Workstations (beds + job-site blocks) come from a block-level whitelist scan
 * over ChunkSnapshots, executed async — the vanilla POI registry has no Bukkit
 * API, so blocks are the observable truth (poi_folder reader parity).
 */
public final class WorldScanHandler {

    private static final double DEFAULT_RADIUS = 128.0;
    private static final int MAX_CANDIDATES = 500;

    /** Column cap for the height grid. The core's WebSocket server runs at the
     *  library default max_size of 1 MiB per frame (it passes no override), and the
     *  same frame also carries the candidates and the map_image PNG. Budgeting
     *  512 KiB of base64 for the grid: 512 KiB * 3/4 = 393216 raw bytes / 4 bytes
     *  per column (two int16 planes) = 98304 columns, i.e. radius ~624 at step 4.
     *  Beyond that the grid is dropped with a warning — the same degradation as a
     *  failed sketch render, rather than an oversized frame that would close the
     *  connection and lose the ENTIRE scan, POI candidates included. */
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private final Plugin plugin;
    private final BridgeClient client;
    private final String worldId;

    /** Job-site block -> villager profession, mirroring the NBT poi reader's naming.
     *  Beds are handled separately via Tag.BEDS (all colors) -> "home". */
    private static final java.util.Map<Material, String> JOB_SITES = java.util.Map.ofEntries(
            java.util.Map.entry(Material.COMPOSTER, "farmer"),
            java.util.Map.entry(Material.BARREL, "fisherman"),
            java.util.Map.entry(Material.SMOKER, "butcher"),
            java.util.Map.entry(Material.BLAST_FURNACE, "armorer"),
            java.util.Map.entry(Material.CARTOGRAPHY_TABLE, "cartographer"),
            java.util.Map.entry(Material.FLETCHING_TABLE, "fletcher"),
            java.util.Map.entry(Material.BREWING_STAND, "cleric"),
            java.util.Map.entry(Material.CAULDRON, "leatherworker"),
            java.util.Map.entry(Material.WATER_CAULDRON, "leatherworker"),
            java.util.Map.entry(Material.LAVA_CAULDRON, "leatherworker"),
            java.util.Map.entry(Material.POWDER_SNOW_CAULDRON, "leatherworker"),
            java.util.Map.entry(Material.LECTERN, "librarian"),
            java.util.Map.entry(Material.LOOM, "shepherd"),
            java.util.Map.entry(Material.SMITHING_TABLE, "toolsmith"),
            java.util.Map.entry(Material.STONECUTTER, "mason"),
            java.util.Map.entry(Material.GRINDSTONE, "weaponsmith"));

    public WorldScanHandler(Plugin plugin, BridgeClient client, String worldId) {
        this.plugin = plugin;
        this.client = client;
        this.worldId = worldId;
    }

    public void handle(JsonObject msg) {
        String correlationId = msg.get("msg_id").getAsString();

        World world = plugin.getServer().getWorlds().get(0); // same binding as resolveWorldId()
        Location spawn = world.getSpawnLocation();
        double centerX = hasNonNull(msg, "center_x") ? msg.get("center_x").getAsDouble() : spawn.getX();
        double centerZ = hasNonNull(msg, "center_z") ? msg.get("center_z").getAsDouble() : spawn.getZ();
        double radius = hasNonNull(msg, "radius") ? msg.get("radius").getAsDouble() : DEFAULT_RADIUS;

        int minChunkX = (int) Math.floor((centerX - radius) / 16.0);
        int maxChunkX = (int) Math.floor((centerX + radius) / 16.0);
        int minChunkZ = (int) Math.floor((centerZ - radius) / 16.0);
        int maxChunkZ = (int) Math.floor((centerZ + radius) / 16.0);

        // Phase 1: ticket + terrain-load every chunk. Entity data loads ASYNC after the
        // terrain (Paper stores entities separately) — a plugin ticket keeps the chunk
        // active so entity loading actually happens; without it a freshly loaded chunk
        // reports an empty getEntities() and the scan silently misses markers.
        final java.util.List<int[]> coords = new java.util.ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                world.addPluginChunkTicket(cx, cz, plugin);
                world.getChunkAt(cx, cz); // sync terrain load
                coords.add(new int[] {cx, cz});
            }
        }

        final double fCenterX = centerX;
        final double fCenterZ = centerZ;
        final double fRadius = radius;

        // Phase 2: poll until every chunk has its entities loaded (or timeout), then scan.
        new org.bukkit.scheduler.BukkitRunnable() {
            private int waitedTicks = 0;
            private static final int MAX_WAIT_TICKS = 100; // 5s — loud fallback, never hangs

            @Override
            public void run() {
                boolean ready = true;
                for (int[] c : coords) {
                    if (!world.getChunkAt(c[0], c[1]).isEntitiesLoaded()) {
                        ready = false;
                        break;
                    }
                }
                if (!ready && waitedTicks < MAX_WAIT_TICKS) {
                    waitedTicks++;
                    return;
                }
                if (!ready) {
                    plugin.getLogger().warning("world_scan: entity load timed out after 5s; "
                            + "scanning with partially loaded entities.");
                }
                try {
                    scanAndSend(correlationId, world, coords, fCenterX, fCenterZ, fRadius);
                } finally {
                    for (int[] c : coords) {
                        world.removePluginChunkTicket(c[0], c[1], plugin);
                    }
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Runs on the main thread (BukkitRunnable via runTaskTimer). */
    private void scanAndSend(String correlationId, World world, java.util.List<int[]> coords,
                             double centerX, double centerZ, double radius) {
        JsonArray candidates = new JsonArray();
        outer:
        for (int[] c : coords) {
            Chunk chunk = world.getChunkAt(c[0], c[1]);
            for (BlockState state : chunk.getTileEntities()) {
                if (candidates.size() >= MAX_CANDIDATES) break outer;
                scanBlockState(state, candidates);
            }
            for (Entity entity : chunk.getEntities()) {
                if (candidates.size() >= MAX_CANDIDATES) break outer;
                scanEntity(entity, candidates);
            }
        }
        if (candidates.size() >= MAX_CANDIDATES) {
            plugin.getLogger().warning("world_scan truncated at " + MAX_CANDIDATES
                    + " candidates; narrow the bounds.");
        }
        plugin.getLogger().info("world_scan: " + candidates.size()
                + " candidate(s) from " + coords.size() + " chunk(s).");

        // Phase 3: block-level workstation scan (poi_folder parity — the POI
        // registry itself has no Bukkit API, but every workstation is a block).
        // Snapshots are taken on the main thread; the block iteration runs
        // async (289 chunks x ~98k blocks must not stall the tick), then the
        // merge + send hops back to the main thread.
        final java.util.List<ChunkSnapshot> snapshots = new java.util.ArrayList<>();
        for (int[] c : coords) {
            snapshots.add(world.getChunkAt(c[0], c[1]).getChunkSnapshot());
        }
        final int minY = world.getMinHeight();
        final int maxY = world.getMaxHeight();
        final int chunkCount = coords.size();

        // Phase 4: height sampling (ADR-043). MUST run here — on the main thread,
        // synchronously, before the async dispatch below. Two reasons, both binding:
        // ChunkSnapshot exposes no typed heightmap, so the live World API is the only
        // source and that is main-thread-only; and handle() releases every plugin chunk
        // ticket in its finally the moment this method RETURNS, while the async block
        // outlives it — sampling there would read unticketed, possibly unloaded chunks.
        // The existing async work is safe only because ChunkSnapshots are immutable
        // copies. A later getChunkAtAsync rebuild must re-prove this assumption.
        final HeightSamples heights = sampleHeights(world, centerX, centerZ, radius);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            final JsonArray workstations = scanWorkstations(snapshots, minY, maxY);
            final String mapImage = renderSketch(snapshots, centerX, centerZ, radius, minY);
            final JsonObject heightGrid = encodeHeights(heights);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (JsonElement e : workstations) {
                    if (candidates.size() >= MAX_CANDIDATES) {
                        plugin.getLogger().warning("world_scan truncated at "
                                + MAX_CANDIDATES + " candidates; narrow the bounds.");
                        break;
                    }
                    candidates.add(e);
                }
                plugin.getLogger().info("world_scan: " + candidates.size()
                        + " candidate(s) from " + chunkCount + " chunk(s).");

                JsonObject bounds = new JsonObject();
                bounds.addProperty("center_x", centerX);
                bounds.addProperty("center_z", centerZ);
                bounds.addProperty("radius", radius);
                client.send(WireSender.worldScanResult(
                        correlationId, candidates, bounds, mapImage, heightGrid));
            });
        });
    }

    private void scanBlockState(BlockState state, JsonArray out) {
        if (!(state instanceof Sign sign)) {
            return;
        }
        StringBuilder allText = new StringBuilder();
        String name = null;
        for (Component line : sign.getSide(Side.FRONT).lines()) {
            String plain = PLAIN.serialize(line).trim();
            if (plain.isEmpty()) {
                continue;
            }
            if (name == null) {
                name = plain; // first non-empty line names the candidate
            }
            if (!allText.isEmpty()) {
                allText.append(" | ");
            }
            allText.append(plain);
        }
        if (name == null) {
            return; // blank sign — no authoring signal
        }
        JsonObject raw = new JsonObject();
        raw.addProperty("text", allText.toString());
        out.add(candidate(name, "sign",
                state.getX() + 0.5, state.getY(), state.getZ() + 0.5, raw));
    }

    private void scanEntity(Entity entity, JsonArray out) {
        if (CitizensAPI.getNPCRegistry().isNPC(entity)) {
            return; // never scan input
        }
        Location loc = entity.getLocation();

        if (entity instanceof Villager villager) {
            String profession = villager.getProfession().getKey().getKey();
            String name = customNameOrNull(entity);
            JsonObject raw = new JsonObject();
            raw.addProperty("profession", profession);
            out.add(candidate(
                    name != null ? name : "Villager (" + profession + ")",
                    "villager", loc.getX(), loc.getY(), loc.getZ(), raw));
            return;
        }
        if (entity instanceof TextDisplay display) {
            String text = PLAIN.serialize(display.text()).trim();
            if (text.isEmpty()) {
                return;
            }
            JsonObject raw = new JsonObject();
            raw.addProperty("text", text);
            out.add(candidate(text, "text_display",
                    loc.getX(), loc.getY(), loc.getZ(), raw));
            return;
        }
        if (entity instanceof Marker) {
            String name = customNameOrNull(entity);
            if (name == null) {
                name = firstTagOrNull(entity); // tag-named marker (the common mapmaker convention)
            }
            JsonObject raw = new JsonObject();
            JsonArray tags = new JsonArray();
            for (String t : entity.getScoreboardTags()) {
                tags.add(t);
            }
            if (!tags.isEmpty()) {
                raw.add("tags", tags);
            }
            out.add(candidate(name != null ? name : "Marker", "marker",
                    loc.getX(), loc.getY(), loc.getZ(), raw));
            return;
        }
        if (entity instanceof ItemFrame frame) {
            if (frame.getItem().getType().isAir()) {
                return; // empty frame — no signal
            }
            String itemType = frame.getItem().getType().getKey().getKey();
            JsonObject raw = new JsonObject();
            raw.addProperty("item_type", itemType);
            out.add(candidate(itemType, "item_frame",
                    loc.getX(), loc.getY(), loc.getZ(), raw));
            return;
        }
        if (entity instanceof LivingEntity) {
            String name = customNameOrNull(entity);
            if (name == null) {
                return; // unnamed mob — ambient, not authored
            }
            JsonObject raw = new JsonObject();
            raw.addProperty("entity_type", entity.getType().getKey().getKey());
            out.add(candidate(name, "named_entity",
                    loc.getX(), loc.getY(), loc.getZ(), raw));
        }
    }

    private static String customNameOrNull(Entity entity) {
        Component custom = entity.customName();
        if (custom == null) {
            return null;
        }
        String plain = PLAIN.serialize(custom).trim();
        return plain.isEmpty() ? null : plain;
    }

    /** PoiCandidateIn wire shape verbatim; source fixed "live_scan". */
    private JsonObject candidate(String name, String poiType,
                                 double x, double y, double z, JsonObject rawData) {
        JsonObject position = new JsonObject();
        position.addProperty("world_id", worldId);
        position.addProperty("x", x);
        position.addProperty("y", y);
        position.addProperty("z", z);

        JsonObject c = new JsonObject();
        c.addProperty("name", name);
        c.addProperty("poi_type", poiType);
        c.addProperty("source", "live_scan");
        c.add("position", position);
        c.add("raw_data", rawData);
        return c;
    }

    /** Wire contract: a bounds field that is absent OR JSON null counts as unset. */
    private static boolean hasNonNull(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull();
    }

    /** First scoreboard tag, or null. Mapmakers name markers via tags, not custom names. */
    private static String firstTagOrNull(Entity entity) {
        for (String tag : entity.getScoreboardTags()) {
            return tag;
        }
        return null;
    }

    /** Runs OFF the main thread on immutable ChunkSnapshots (thread-safe copies).
     *  Beds emit one candidate per HEAD half only (poi_folder parity: one entry
     *  per bed, not per block). */
    private JsonArray scanWorkstations(java.util.List<ChunkSnapshot> snapshots,
                                       int minY, int maxY) {
        JsonArray out = new JsonArray();
        for (ChunkSnapshot snap : snapshots) {
            int baseX = snap.getX() << 4;
            int baseZ = snap.getZ() << 4;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = minY; y < maxY; y++) {
                        Material type = snap.getBlockType(x, y, z);
                        String name;
                        if (Tag.BEDS.isTagged(type)) {
                            BlockData data = snap.getBlockData(x, y, z);
                            if (!(data instanceof org.bukkit.block.data.type.Bed bed)
                                    || bed.getPart() != org.bukkit.block.data.type.Bed.Part.HEAD) {
                                continue;
                            }
                            name = "home";
                        } else {
                            name = JOB_SITES.get(type);
                            if (name == null) {
                                continue;
                            }
                        }
                        JsonObject raw = new JsonObject();
                        raw.addProperty("mc_type", type.getKey().toString());
                        out.add(candidate(name, "workstation",
                                baseX + x + 0.5, y, baseZ + z + 0.5, raw));
                    }
                }
            }
        }
        return out;
    }

    /** Runs OFF the main thread on immutable ChunkSnapshots. Renders the exact
     *  bounds square (center +/- radius) so the frontend anchors the image with
     *  x=center_x-radius, y=center_z-radius, w=h=2*radius — no offset math.
     *  Any failure returns null: the scan must never die for a sketch. */
    private String renderSketch(java.util.List<ChunkSnapshot> snapshots,
                                double centerX, double centerZ, double radius, int minWorldY) {
        try {
            final int size = (int) Math.ceil(radius * 2.0);
            final int scale = 1; //size > 512 ? 1 : 2; // cap image edge at ~1024 px
            final int minBX = (int) Math.floor(centerX - radius);
            final int minBZ = (int) Math.floor(centerZ - radius);

            java.util.Map<Long, ChunkSnapshot> byChunk = new java.util.HashMap<>();
            for (ChunkSnapshot s : snapshots) {
                byChunk.put(chunkKey(s.getX(), s.getZ()), s);
            }

            int[][] heights = new int[size][size];
            int[][] colors = new int[size][size];
            for (int dx = 0; dx < size; dx++) {
                for (int dz = 0; dz < size; dz++) {
                    int wx = minBX + dx;
                    int wz = minBZ + dz;
                    ChunkSnapshot s = byChunk.get(chunkKey(wx >> 4, wz >> 4));
                    if (s == null) { // outside the ticketed chunk box (corner rounding)
                        colors[dx][dz] = 0x101418;
                        heights[dx][dz] = Integer.MIN_VALUE;
                        continue;
                    }
                    int lx = wx & 15;
                    int lz = wz & 15;
                    int y = s.getHighestBlockYAt(lx, lz);
                    Material m = s.getBlockType(lx, y, lz);
                    if (m.isAir() && y > minWorldY) { // API version quirk: highest may point above
                        y -= 1;
                        m = s.getBlockType(lx, y, lz);
                    }
                    colors[dx][dz] = baseColor(m);
                    heights[dx][dz] = y;
                }
            }

            BufferedImage img = new BufferedImage(size * scale, size * scale,
                    BufferedImage.TYPE_INT_RGB);
            for (int dx = 0; dx < size; dx++) {
                for (int dz = 0; dz < size; dz++) {
                    int rgb = shade(colors[dx][dz], heights[dx][dz],
                            dz > 0 ? heights[dx][dz - 1] : heights[dx][dz]);
                    for (int px = 0; px < scale; px++) {
                        for (int pz = 0; pz < scale; pz++) {
                            // world x -> image x, world z -> image y (north up, no flip —
                            // same axis convention as PoiMap's SVG).
                            img.setRGB(dx * scale + px, dz * scale + pz, rgb);
                        }
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("world_scan sketch render failed; sending scan without map.");
            return null;
        }
    }

    /** Sampled height planes plus the geometry needed to describe them on the wire. */
    private record HeightSamples(HeightGrid.Geometry geometry, short[] planes) {
    }

    /** Runs on the MAIN thread while the scan's chunk tickets are still held (see the
     *  call site). Any failure logs a warning and returns null, exactly like
     *  renderSketch: the scan must never die for an optional payload. */
    private HeightSamples sampleHeights(World world, double centerX, double centerZ, double radius) {
        try {
            HeightGrid.Geometry geom =
                    HeightGrid.geometry(centerX, centerZ, radius, HeightGrid.SAMPLE_STEP);
            if (geom == null) {
                plugin.getLogger().warning("world_scan: degenerate bounds (radius " + radius
                        + "); sending scan without height grid.");
                return null;
            }

            long startNanos = System.nanoTime();
            short[] planes = HeightGrid.build(geom, new HeightGrid.ColumnSampler() {
                @Override
                public boolean available(int wx, int wz) {
                    // >> 4, never / 16: division truncates toward zero and picks the
                    // wrong chunk for negative coordinates.
                    return world.isChunkLoaded(wx >> 4, wz >> 4);
                }

                @Override
                public int surfaceY(int wx, int wz) {
                    return world.getHighestBlockYAt(wx, wz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                }

                @Override
                public int floorY(int wx, int wz) {
                    return world.getHighestBlockYAt(wx, wz, HeightMap.OCEAN_FLOOR);
                }
            });
            long millis = (System.nanoTime() - startNanos) / 1_000_000L;

            // C: the live criterion for this slice. The sample box is provably inside the
            // ticketed chunk box, so a non-zero sentinel share means a chunk failed to stay
            // loaded — "no data", never bad geometry. Expected 0%.
            int sentinels = HeightGrid.countSentinelColumns(planes);
            int total = geom.cells();
            plugin.getLogger().info("world_scan: height grid " + geom.width() + "x" + geom.height()
                    + " step " + geom.step() + ", " + sentinels + "/" + total
                    + " sentinel column(s) (" + (total == 0 ? 0 : (sentinels * 100L) / total)
                    + "%), " + millis + " ms.");
            return new HeightSamples(geom, planes);
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "world_scan height sampling failed; sending scan without height grid.", e);
            return null;
        }
    }

    /** Runs OFF the main thread on the finished sample arrays (immutable from here on),
     *  mirroring how renderSketch keeps encoding work off the tick. */
    private JsonObject encodeHeights(HeightSamples samples) {
        if (samples == null) {
            return null; // sampling already logged why
        }
        try {
            return WireSender.heightGrid(samples.geometry(), HeightGrid.encode(samples.planes()));
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "world_scan height encoding failed; sending scan without height grid.", e);
            return null;
        }
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** Classic map-style slope shading: higher than the northern neighbor reads
     *  brighter, lower reads darker, flat stays. */
    private static int shade(int rgb, int y, int northY) {
        if (y == Integer.MIN_VALUE) {
            return rgb; // out-of-box background, no shading
        }
        double f = y > northY ? 1.15 : (y < northY ? 0.78 : 1.0);
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((rgb & 0xFF) * f));
        return (r << 16) | (g << 8) | b;
    }

    /** Coarse material -> color for a schematic sketch — readable, not pretty.
     *  Tag groups first, then name heuristics, then neutral gray. */
    private static int baseColor(Material m) {
        if (m == Material.WATER) return 0x3B6BD1;
        if (m == Material.LAVA) return 0xD96C2C;
        if (m == Material.GRASS_BLOCK) return 0x6DA24A;
        if (Tag.LEAVES.isTagged(m)) return 0x2E5D2E;
        if (Tag.LOGS.isTagged(m)) return 0x5C4326;
        if (Tag.SAND.isTagged(m)) return 0xD7CD8F;
        if (Tag.PLANKS.isTagged(m)) return 0x9C7B4D;
        if (Tag.ICE.isTagged(m)) return 0xA8C8E8;
        String n = m.name();
        if (n.contains("SNOW")) return 0xE8ECEF;
        if (n.contains("STONE") || n.contains("COBBLE") || n.contains("DEEPSLATE")
                || n.contains("ANDESITE") || n.contains("GRAVEL")) return 0x7E7E7E;
        if (n.contains("DIRT") || n.contains("PATH") || n.contains("FARMLAND")
                || n.contains("MUD") || n.contains("PODZOL")) return 0x8A6844;
        if (n.contains("TERRACOTTA") || n.contains("BRICK")) return 0xA5593F;
        if (n.contains("SEAGRASS") || n.contains("KELP") || n.contains("LILY")) return 0x3B6BD1;
        return 0x8A8A8A;
    }
}