package dev.memnos.controlbridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * Pure mechanics for the world_scan height grid (ADR-043 W1.2/W1.3, slice 1).
 *
 * <p>Two int16 planes per scan — surface (MOTION_BLOCKING_NO_LEAVES) and floor
 * (OCEAN_FLOOR) — sampled at {@link #SAMPLE_STEP}, concatenated little-endian,
 * Base64. Everything in here is static and pure (class B, see HeightGridTest);
 * the only impure part of the feature is the {@link ColumnSampler} the handler
 * closes over the live world.
 *
 * <p>The grid origin travels explicitly on the wire as origin_x/origin_z, so no
 * reader ever re-derives the floor rounding (deviation/precision in the plan
 * phase, ratified by owner approval — ADR-043 W1.3 self-describing format).
 *
 * <p>C (live proof, not mechanizable here): after a real scan the server log
 * must show grid dimensions, sentinel share (expected 0% with today's chunk
 * derivation) and duration; water plausibility (surface &gt; floor over known
 * water) is proven visually in slice 4.
 */
public final class HeightGrid {

    /** One sample per 4x4 column block (ADR-043 W1.3, fixed in v0). */
    public static final int SAMPLE_STEP = 4;

    /**
     * Marker for columns whose chunk the scan did not load. -32768 sits outside
     * the valid Y range (-64..320), so it can never collide with a real height.
     * Unreachable with today's chunk derivation (getChunkAt generates), but it
     * is the format contract slices 2/4 handle — and the guarantee that sampling
     * never forces chunk generation the scan does not already cause.
     */
    public static final short SENTINEL = Short.MIN_VALUE;

    private HeightGrid() {}

    /**
     * Sample raster geometry. Positions are {@code originX + i * step} along +x
     * and {@code originZ + j * step} along +z; width/height are the sample
     * counts per axis and travel explicitly on the wire (the reader never
     * derives them from bounds).
     */
    public record Geometry(int originX, int originZ, int width, int height, int step) {
        /** Number of columns per plane ({@code width * height}). */
        public int cells() {
            return width * height;
        }
    }

    /**
     * Geometry from the scan bounds, or {@code null} iff {@code radius <= 0}
     * (degenerate bounds carry no raster — the caller treats null like the
     * failure path: warn, omit the field). For every {@code radius > 0} the
     * function is total with {@code width >= 1} and {@code height >= 1}.
     *
     * <p>The origin is {@code Math.floor(center - radius)} — the same anchor
     * renderSketch uses, so the relief raster and the sketch PNG share it.
     *
     * @throws IllegalArgumentException if {@code step <= 0}
     */
    public static Geometry geometry(double centerX, double centerZ, double radius, int step) {
        if (step <= 0) {
            throw new IllegalArgumentException("step must be > 0, got " + step);
        }
        if (radius <= 0) {
            return null; // K2: degenerate bounds carry no raster
        }
        int originX = (int) Math.floor(centerX - radius);
        int originZ = (int) Math.floor(centerZ - radius);
        // floor(2r/step)+1 samples per axis: radius 256 / step 4 -> 129 (the ADR
        // anchor). Bounds are square (center + radius), so both axes match.
        int samples = (int) Math.floor(2.0 * radius / step) + 1;
        return new Geometry(originX, originZ, samples, samples, step);
    }

    /**
     * Column access, cut as an interface so a fake can drive the sentinel path
     * in tests. Implementations must not allocate per column.
     */
    public interface ColumnSampler {
        boolean available(int wx, int wz);

        int surfaceY(int wx, int wz);

        int floorY(int wx, int wz);
    }

    /**
     * Both planes concatenated: {@code [0, cells)} = surface,
     * {@code [cells, 2*cells)} = floor, row-major {@code idx = j * width + i}
     * (i along +x, j along +z). One array allocated up front, nothing per
     * column. An unavailable column writes {@link #SENTINEL} to both planes.
     */
    public static short[] build(Geometry geom, ColumnSampler sampler) {
        int cells = geom.width() * geom.height();
        short[] planes = new short[cells * 2];
        for (int j = 0; j < geom.height(); j++) {
            int wz = geom.originZ() + j * geom.step();
            for (int i = 0; i < geom.width(); i++) {
                int wx = geom.originX() + i * geom.step();
                int idx = j * geom.width() + i;
                if (sampler.available(wx, wz)) {
                    planes[idx] = (short) sampler.surfaceY(wx, wz);
                    planes[cells + idx] = (short) sampler.floorY(wx, wz);
                } else {
                    planes[idx] = SENTINEL;
                    planes[cells + idx] = SENTINEL;
                }
            }
        }
        return planes;
    }

    /** int16 little-endian, then Base64 (the wire's data_base64). */
    public static String encode(short[] planes) {
        ByteBuffer buffer = ByteBuffer.allocate(planes.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asShortBuffer().put(planes);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    /**
     * Sentinel COLUMNS, not cells, for the live-proof log line. build writes
     * both planes together, so counting the surface plane counts columns.
     */
    public static int countSentinelColumns(short[] planes) {
        int cells = planes.length / 2;
        int count = 0;
        for (int idx = 0; idx < cells; idx++) {
            if (planes[idx] == SENTINEL) {
                count++;
            }
        }
        return count;
    }
}