package com.ibuild;

/**
 * Geometrie pure (aucune dependance Minecraft) utilisee par le mode client :
 * transforme un fill (solid / hollow / walls / outline) en pavés pleins, puis decoupe ces pavés
 * en morceaux dont le volume tient dans la limite d'une commande /fill.
 */
final class Boxes {
    static final int SOLID = 0, HOLLOW = 1, WALLS = 2, OUTLINE = 3; // memes valeurs que Ops

    interface Sink { void box(int x0, int y0, int z0, int x1, int y1, int z1); }

    private Boxes() {}

    /** Les coins doivent etre ordonnes (x0 <= x1, etc.). Les pavés emis couvrent exactement les cellules du mode. */
    static void decompose(int x0, int y0, int z0, int x1, int y1, int z1, int mode, Sink s) {
        switch (mode) {
            case SOLID -> s.box(x0, y0, z0, x1, y1, z1);
            case HOLLOW -> {
                if (x1 - x0 < 2 || y1 - y0 < 2 || z1 - z0 < 2) { s.box(x0, y0, z0, x1, y1, z1); return; }
                s.box(x0, y0, z0, x0, y1, z1);
                s.box(x1, y0, z0, x1, y1, z1);
                s.box(x0 + 1, y0, z0, x1 - 1, y0, z1);
                s.box(x0 + 1, y1, z0, x1 - 1, y1, z1);
                s.box(x0 + 1, y0 + 1, z0, x1 - 1, y1 - 1, z0);
                s.box(x0 + 1, y0 + 1, z1, x1 - 1, y1 - 1, z1);
            }
            case WALLS -> {
                s.box(x0, y0, z0, x0, y1, z1);
                if (x1 != x0) s.box(x1, y0, z0, x1, y1, z1);
                if (x1 - x0 >= 2) {
                    s.box(x0 + 1, y0, z0, x1 - 1, y1, z0);
                    if (z1 != z0) s.box(x0 + 1, y0, z1, x1 - 1, y1, z1);
                }
            }
            default -> { // OUTLINE : cellules ayant au moins 2 coordonnees sur une arete de la boite
                int[] ys = y0 == y1 ? new int[]{y0} : new int[]{y0, y1};
                int[] zs = z0 == z1 ? new int[]{z0} : new int[]{z0, z1};
                int[] xs = x0 == x1 ? new int[]{x0} : new int[]{x0, x1};
                for (int y : ys) for (int z : zs) s.box(x0, y, z, x1, y, z);
                for (int x : xs) for (int z : zs) s.box(x, y0, z, x, y1, z);
                for (int x : xs) for (int y : ys) s.box(x, y, z0, x, y, z1);
            }
        }
    }

    /** Decoupe un pave en morceaux de volume <= limit (coupe toujours le plus long cote en deux). */
    static void split(int x0, int y0, int z0, int x1, int y1, int z1, long limit, Sink s) {
        long dx = (long) x1 - x0 + 1, dy = (long) y1 - y0 + 1, dz = (long) z1 - z0 + 1;
        if (dx * dy * dz <= limit) { s.box(x0, y0, z0, x1, y1, z1); return; }
        if (dx >= dy && dx >= dz) {
            int m = x0 + (int) (dx / 2) - 1;
            split(x0, y0, z0, m, y1, z1, limit, s);
            split(m + 1, y0, z0, x1, y1, z1, limit, s);
        } else if (dy >= dz) {
            int m = y0 + (int) (dy / 2) - 1;
            split(x0, y0, z0, x1, m, z1, limit, s);
            split(x0, m + 1, z0, x1, y1, z1, limit, s);
        } else {
            int m = z0 + (int) (dz / 2) - 1;
            split(x0, y0, z0, x1, y1, m, limit, s);
            split(x0, y0, m + 1, x1, y1, z1, limit, s);
        }
    }
}
