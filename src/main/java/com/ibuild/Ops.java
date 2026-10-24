package com.ibuild;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Liste compacte des operations d'un build (lue en streaming, sans arbre JSON en memoire).
 * Chaque operation = 7 int : x0 y0 z0 x1 y1 z1 (coins ordonnes, inclus) + meta (mode << 24 | index de palette).
 * Un "set" est stocke comme un "fill" solide d'un seul bloc.
 */
final class Ops {
    static final int SOLID = 0, HOLLOW = 1, WALLS = 2, OUTLINE = 3;
    static final int MAX_COORD = 30_000;
    static final long MAX_OP_BLOCKS = Integer.MAX_VALUE;
    static final int STRIDE = 7;

    int[] d = new int[STRIDE * 256];
    int n = 0;
    final List<BlockState> palette = new ArrayList<>();

    /** Remplit les x de xa a xb (inclus) pour la ligne (y, z). */
    interface Sink { void run(int y, int z, int xa, int xb); }

    void add(int x0, int y0, int z0, int x1, int y1, int z1, int mode, int pal) {
        if ((n + 1) * STRIDE > d.length) d = Arrays.copyOf(d, d.length * 2);
        int o = n++ * STRIDE;
        d[o] = Math.min(x0, x1); d[o + 1] = Math.min(y0, y1); d[o + 2] = Math.min(z0, z1);
        d[o + 3] = Math.max(x0, x1); d[o + 4] = Math.max(y0, y1); d[o + 5] = Math.max(z0, z1);
        d[o + 6] = (mode << 24) | pal;
    }

    // ---------- lecture en streaming ----------

    /** Verifie seulement la syntaxe JSON (aucune allocation proportionnelle a la taille du fichier). */
    @SuppressWarnings("deprecation") // setLenient : meme tolerance que JsonParser.parseReader() utilise avant
    static String validate(Path file) {
        try (JsonReader r = new JsonReader(Files.newBufferedReader(file))) {
            r.setLenient(true);
            if (r.peek() != JsonToken.BEGIN_OBJECT) return "objet JSON attendu";
            r.skipValue();
            return null;
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    static Ops read(Path file, HolderLookup<Block> lookup) throws IOException {
        Ops ops = new Ops();
        Map<String, Integer> cache = new HashMap<>();
        try (JsonReader r = new JsonReader(Files.newBufferedReader(file))) {
            r.setLenient(true);
            if (r.peek() != JsonToken.BEGIN_OBJECT) throw new IllegalArgumentException("objet JSON attendu");
            r.beginObject();
            boolean found = false;
            while (r.hasNext()) {
                if (r.nextName().equals("ops")) {
                    found = true;
                    readArray(r, ops, lookup, cache);
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
            if (!found) throw new IllegalArgumentException("champ \"ops\" manquant");
        }
        return ops;
    }

    private static void readArray(JsonReader r, Ops ops, HolderLookup<Block> lookup, Map<String, Integer> cache) throws IOException {
        r.beginArray();
        int idx = 0;
        while (r.hasNext()) {
            idx++;
            String type = null, block = null, modeStr = null;
            int[] pos = null, from = null, to = null;
            r.beginObject();
            while (r.hasNext()) {
                switch (r.nextName()) {
                    case "type" -> type = r.nextString();
                    case "block" -> block = r.nextString();
                    case "mode" -> modeStr = r.nextString();
                    case "pos" -> pos = vec(r, idx);
                    case "from" -> from = vec(r, idx);
                    case "to" -> to = vec(r, idx);
                    default -> r.skipValue();
                }
            }
            r.endObject();

            if (type == null) throw new IllegalArgumentException("op " + idx + " : champ \"type\" manquant");
            if (block == null) throw new IllegalArgumentException("op " + idx + " : champ \"block\" manquant");

            Integer pal = cache.get(block);
            if (pal == null) {
                BlockState st;
                try {
                    st = BlockStateParser.parseForBlock(lookup, block, false).blockState();
                } catch (Exception e) {
                    throw new IllegalArgumentException("op " + idx + " : bloc invalide \"" + block + "\" (" + e.getMessage() + ")");
                }
                if (ops.palette.size() >= 0xFFFF) throw new IllegalArgumentException("trop de blocs differents dans le build");
                pal = ops.palette.size();
                ops.palette.add(st);
                cache.put(block, pal);
            }

            switch (type) {
                case "set" -> {
                    if (pos == null) throw new IllegalArgumentException("op " + idx + " : coordonnees [x,y,z] attendues");
                    ops.add(pos[0], pos[1], pos[2], pos[0], pos[1], pos[2], SOLID, pal);
                }
                case "fill" -> {
                    if (from == null || to == null) throw new IllegalArgumentException("op " + idx + " : coordonnees [x,y,z] attendues");
                    int mode = switch (modeStr == null ? "solid" : modeStr) {
                        case "solid" -> SOLID;
                        case "hollow" -> HOLLOW;
                        case "walls" -> WALLS;
                        case "outline" -> OUTLINE;
                        default -> throw new IllegalArgumentException("op " + idx + " : mode inconnu " + modeStr);
                    };
                    long vol = (long) (Math.abs(from[0] - to[0]) + 1) * (Math.abs(from[1] - to[1]) + 1) * (Math.abs(from[2] - to[2]) + 1);
                    if (vol > MAX_OP_BLOCKS) throw new IllegalArgumentException("op " + idx + " trop grosse (" + vol + " blocs)");
                    ops.add(from[0], from[1], from[2], to[0], to[1], to[2], mode, pal);
                }
                default -> throw new IllegalArgumentException("op " + idx + " : type inconnu " + type);
            }
        }
        r.endArray();
    }

    private static int[] vec(JsonReader r, int idx) throws IOException {
        r.beginArray();
        int[] v = new int[3];
        int i = 0;
        while (r.hasNext()) {
            int val = r.nextInt();
            if (i < 3) v[i] = val;
            i++;
        }
        r.endArray();
        if (i != 3) throw new IllegalArgumentException("op " + idx + " : coordonnees [x,y,z] attendues");
        for (int k : v) {
            if (Math.abs(k) > MAX_COORD) throw new IllegalArgumentException("op " + idx + " : coordonnee hors limite (" + MAX_COORD + ")");
        }
        return v;
    }

    // ---------- geometrie ----------

    /** Nombre de blocs ecrits par toutes les ops (borne haute : les recouvrements sont comptes plusieurs fois). */
    long count() {
        long total = 0;
        for (int i = 0; i < n; i++) {
            int o = i * STRIDE;
            total += cells(d[o + 3] - d[o] + 1, d[o + 4] - d[o + 1] + 1, d[o + 5] - d[o + 2] + 1, d[o + 6] >>> 24);
        }
        return total;
    }

    static long cells(long a, long b, long c, int mode) {
        return switch (mode) {
            case SOLID -> a * b * c;
            case HOLLOW -> a * b * c - Math.max(0, a - 2) * Math.max(0, b - 2) * Math.max(0, c - 2);
            case WALLS -> (a * c - Math.max(0, a - 2) * Math.max(0, c - 2)) * b;
            default -> {
                long ea = a == 1 ? 1 : 2, eb = b == 1 ? 1 : 2, ec = c == 1 ? 1 : 2;
                yield ea * eb * c + ea * ec * b + eb * ec * a - 2 * ea * eb * ec;
            }
        };
    }

    /**
     * Enumere, ligne par ligne (y, z), les cellules d'une boite selon son mode, limitees au pave de decoupe
     * [cx0..cx1] x [cy0..cy1] x [cz0..cz1]. Les faces de la boite sont celles de la boite NON decoupee.
     * Ne parcourt jamais l'interieur d'une boite creuse : cout proportionnel a la surface.
     */
    static void rows(int x0, int y0, int z0, int x1, int y1, int z1, int mode,
                     int cx0, int cx1, int cy0, int cy1, int cz0, int cz1, Sink sink) {
        int ya = Math.max(y0, cy0), yb = Math.min(y1, cy1);
        int za = Math.max(z0, cz0), zb = Math.min(z1, cz1);
        int xa = Math.max(x0, cx0), xb = Math.min(x1, cx1);
        if (ya > yb || za > zb || xa > xb) return;
        boolean end0 = x0 >= cx0 && x0 <= cx1;
        boolean end1 = x1 != x0 && x1 >= cx0 && x1 <= cx1;
        for (int y = ya; y <= yb; y++) {
            boolean ey = y == y0 || y == y1;
            for (int z = za; z <= zb; z++) {
                boolean ez = z == z0 || z == z1;
                int kind; // 0 rien, 1 seulement les deux extremites en x, 2 toute la ligne
                switch (mode) {
                    case SOLID -> kind = 2;
                    case HOLLOW -> kind = (ey || ez) ? 2 : 1;
                    case WALLS -> kind = ez ? 2 : 1;
                    default -> kind = (ey && ez) ? 2 : (ey || ez) ? 1 : 0;
                }
                if (kind == 2) {
                    sink.run(y, z, xa, xb);
                } else if (kind == 1) {
                    if (end0) sink.run(y, z, x0, x0);
                    if (end1) sink.run(y, z, x1, x1);
                }
            }
        }
    }
}
