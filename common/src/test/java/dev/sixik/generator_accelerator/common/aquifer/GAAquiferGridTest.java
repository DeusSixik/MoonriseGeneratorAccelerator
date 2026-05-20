package dev.sixik.generator_accelerator.common.aquifer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GAAquiferGridTest {
    @Test
    void nearestMatchesReferenceSearch() {
        int gridSizeX = 7;
        int gridSizeY = 6;
        int gridSizeZ = 7;
        int minGridX = -3;
        int minGridY = -2;
        int minGridZ = -3;
        int size = gridSizeX * gridSizeY * gridSizeZ;
        int[] xs = new int[size];
        int[] ys = new int[size];
        int[] zs = new int[size];
        for (int y = 0; y < gridSizeY; y++) {
            for (int z = 0; z < gridSizeZ; z++) {
                for (int x = 0; x < gridSizeX; x++) {
                    int index = ((y * gridSizeZ) + z) * gridSizeX + x;
                    int gx = x + minGridX;
                    int gy = y + minGridY;
                    int gz = z + minGridZ;
                    xs[index] = gx * 16 + ((gx * 13 + gy * 7 + gz * 5) & 9);
                    ys[index] = gy * 12 + ((gx * 3 + gy * 11 + gz * 17) & 7);
                    zs[index] = gz * 16 + ((gx * 19 + gy * 2 + gz * 23) & 9);
                }
            }
        }
        GAAquiferGrid grid = new GAAquiferGrid(gridSizeX, gridSizeZ, minGridX, minGridY, minGridZ, xs, ys, zs);
        GAAquiferNearest actual = new GAAquiferNearest();
        GAAquiferNearest actualBand = new GAAquiferNearest();
        GAAquiferNearest expected = new GAAquiferNearest();
        GAAquiferColumnBandNearest band = new GAAquiferColumnBandNearest();

        for (int x = -20; x <= 36; x += 7) {
            for (int y = -10; y <= 28; y += 6) {
                for (int z = -20; z <= 36; z += 7) {
                    grid.nearest(x, y, z, actual);
                    grid.nearestColumnBand(x, y, z, band, actualBand);
                    referenceNearest(gridSizeX, gridSizeZ, minGridX, minGridY, minGridZ, xs, ys, zs, x, y, z, expected);
                    assertEquals(expected.dist1, actual.dist1);
                    assertEquals(expected.dist2, actual.dist2);
                    assertEquals(expected.dist3, actual.dist3);
                    assertEquals(expected.idx1, actual.idx1);
                    assertEquals(expected.idx2, actual.idx2);
                    assertEquals(expected.idx3, actual.idx3);
                    assertEquals(expected.dist1, actualBand.dist1);
                    assertEquals(expected.dist2, actualBand.dist2);
                    assertEquals(expected.dist3, actualBand.dist3);
                    assertEquals(expected.idx1, actualBand.idx1);
                    assertEquals(expected.idx2, actualBand.idx2);
                    assertEquals(expected.idx3, actualBand.idx3);
                }
            }
        }
    }

    @Test
    void nearestTieOrderMatchesVanillaLoopOrder() {
        int[] xs = new int[12];
        int[] ys = new int[12];
        int[] zs = new int[12];
        GAAquiferGrid grid = new GAAquiferGrid(2, 2, 0, -1, 0, xs, ys, zs);
        GAAquiferNearest nearest = new GAAquiferNearest();
        GAAquiferNearest bandNearest = new GAAquiferNearest();
        GAAquiferColumnBandNearest band = new GAAquiferColumnBandNearest();

        grid.nearest(5, -1, 5, nearest);
        grid.nearestColumnBand(5, -1, 5, band, bandNearest);

        assertEquals(11, nearest.idx1);
        assertEquals(11, bandNearest.idx1);
    }

    private static void referenceNearest(
            int gridSizeX,
            int gridSizeZ,
            int minGridX,
            int minGridY,
            int minGridZ,
            int[] xs,
            int[] ys,
            int[] zs,
            int x,
            int y,
            int z,
            GAAquiferNearest out
    ) {
        int gx = (x - 5) >> 4;
        int gy = GAAquiferGrid.floorDiv12(y + 1);
        int gz = (z - 5) >> 4;
        out.reset();
        for (int bx = gx; bx <= gx + 1; bx++) {
            for (int by = gy - 1; by <= gy + 1; by++) {
                for (int bz = gz; bz <= gz + 1; bz++) {
                    int index = (((by - minGridY) * gridSizeZ) + (bz - minGridZ)) * gridSizeX + (bx - minGridX);
                    int dx = xs[index] - x;
                    int dy = ys[index] - y;
                    int dz = zs[index] - z;
                    out.accept(index, dx * dx + dy * dy + dz * dz);
                }
            }
        }
    }
}
