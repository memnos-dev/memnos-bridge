package dev.memnos.controlbridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** All class B — behavioral obligations of the pure height-grid mechanics (ADR-043 slice 1). */
class HeightGridTest {

    /** Fake sampler encoding the queried position into the value, so index order is provable. */
    private static final HeightGrid.ColumnSampler POSITION_ENCODING =
            new HeightGrid.ColumnSampler() {
                @Override
                public boolean available(int wx, int wz) {
                    return true;
                }

                @Override
                public int surfaceY(int wx, int wz) {
                    return wx * 10 + wz;
                }

                @Override
                public int floorY(int wx, int wz) {
                    return -(wx * 10 + wz) - 1;
                }
            };

    // B: the ADR anchor itself — radius 256 / step 4 -> 129 samples; radius 128 -> 65.
    @Test
    void geometryHitsTheAdrAnchor() {
        HeightGrid.Geometry g256 = HeightGrid.geometry(0.0, 0.0, 256.0, 4);
        assertEquals(129, g256.width());
        assertEquals(129, g256.height());
        HeightGrid.Geometry g128 = HeightGrid.geometry(0.0, 0.0, 128.0, 4);
        assertEquals(65, g128.width());
        assertEquals(65, g128.height());
    }

    // B: non-divisible radius — 99/4 -> 50 samples, last at origin+196, the trailing
    // 2 blocks stay uncovered (deliberate).
    @Test
    void geometryFloorsNonDivisibleRadius() {
        HeightGrid.Geometry g = HeightGrid.geometry(0.0, 0.0, 99.0, 4);
        assertEquals(50, g.width());
        assertEquals(-99, g.originX());
        assertEquals(97, g.originX() + (g.width() - 1) * g.step()); // origin + 196
    }

    // B: fractional center — origin == floor(center - radius), the renderSketch anchor,
    // including the negative side where floor != truncation.
    @Test
    void geometryAnchorsOnFloorOfCenterMinusRadius() {
        assertEquals(7, HeightGrid.geometry(10.7, 10.7, 3.0, 4).originX());
        assertEquals(-3, HeightGrid.geometry(-0.5, -0.5, 2.0, 4).originZ());
    }

    // B: row-major idx = j * width + i (i along +x), and the COMPLETE surface plane
    // precedes the floor plane.
    @Test
    void buildIsRowMajorWithSurfacePlaneFirst() {
        HeightGrid.Geometry g = HeightGrid.geometry(6.0, 6.0, 6.0, 4); // origin 0, 4x4
        assertEquals(4, g.width());
        short[] planes = HeightGrid.build(g, POSITION_ENCODING);
        int cells = g.width() * g.height();
        assertEquals(cells * 2, planes.length);
        for (int j = 0; j < g.height(); j++) {
            for (int i = 0; i < g.width(); i++) {
                int wx = g.originX() + i * g.step();
                int wz = g.originZ() + j * g.step();
                int idx = j * g.width() + i;
                assertEquals((short) (wx * 10 + wz), planes[idx]);
                assertEquals((short) (-(wx * 10 + wz) - 1), planes[cells + idx]);
            }
        }
    }

    // B: an unavailable chunk writes SENTINEL to BOTH planes for its columns and
    // leaves every other cell untouched.
    @Test
    void buildWritesSentinelToBothPlanesForUnavailableColumns() {
        HeightGrid.Geometry g = HeightGrid.geometry(16.0, 16.0, 16.0, 4); // origin 0, 9x9
        HeightGrid.ColumnSampler chunkZeroMissing =
                new HeightGrid.ColumnSampler() {
                    @Override
                    public boolean available(int wx, int wz) {
                        return !((wx >> 4) == 0 && (wz >> 4) == 0);
                    }

                    @Override
                    public int surfaceY(int wx, int wz) {
                        return 64;
                    }

                    @Override
                    public int floorY(int wx, int wz) {
                        return 60;
                    }
                };
        short[] planes = HeightGrid.build(g, chunkZeroMissing);
        int cells = g.width() * g.height();
        // Samples 0,4,8,12 fall into chunk (0,0) on each axis -> 4x4 = 16 sentinel columns.
        assertEquals(16, HeightGrid.countSentinelColumns(planes));
        int insideIdx = 0; // (wx 0, wz 0) — inside the missing chunk
        assertEquals(HeightGrid.SENTINEL, planes[insideIdx]);
        assertEquals(HeightGrid.SENTINEL, planes[cells + insideIdx]);
        int outsideIdx = 8; // (wx 32, wz 0) — chunk (2,0), untouched
        assertEquals((short) 64, planes[outsideIdx]);
        assertEquals((short) 60, planes[cells + outsideIdx]);
    }

    // B: int16 little-endian byte layout, proven on a known smallest array via
    // Base64 round-trip.
    @Test
    void encodeIsInt16LittleEndian() {
        String b64 = HeightGrid.encode(new short[] {1, -2, 258, Short.MIN_VALUE});
        byte[] expected = {0x01, 0x00, (byte) 0xFE, (byte) 0xFF, 0x02, 0x01, 0x00, (byte) 0x80};
        assertArrayEquals(expected, Base64.getDecoder().decode(b64));
    }

    // B: countSentinelColumns counts COLUMNS, not cells — a floor-only sentinel cell
    // (never produced by build, but representable) must not inflate the count.
    @Test
    void countSentinelColumnsCountsColumnsNotCells() {
        short[] planes = {HeightGrid.SENTINEL, 5, HeightGrid.SENTINEL, HeightGrid.SENTINEL};
        assertEquals(1, HeightGrid.countSentinelColumns(planes));
    }

    // B (K2): radius <= 0 -> null (degenerate bounds, caller's failure path); smallest
    // positive radius -> total, 1x1 grid anchored at floor(center - radius). step <= 0
    // is a programming error, not a data case.
    @Test
    void geometryNullContractIsMechanical() {
        assertNull(HeightGrid.geometry(0.0, 0.0, 0.0, 4));
        assertNull(HeightGrid.geometry(0.0, 0.0, -1.0, 4));
        HeightGrid.Geometry g = HeightGrid.geometry(10.5, 10.5, 1.0, 4);
        assertEquals(1, g.width());
        assertEquals(1, g.height());
        assertEquals(9, g.originX()); // floor(10.5 - 1)
        assertThrows(IllegalArgumentException.class, () -> HeightGrid.geometry(0.0, 0.0, 5.0, 0));
    }
}