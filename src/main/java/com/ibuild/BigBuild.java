package com.ibuild;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Mode optimise, utilise automatiquement au-dessus de {@link Builds#BIG_THRESHOLD} blocs.
 *
 * Differences avec le mode classique :
 *  - aucune structure par bloc : le build reste une liste d'operations (28 octets chacune) ;
 *  - le monde est traite colonne de chunk par colonne de chunk : chaque colonne est resolue dans un
 *    tampon de 16x16xHauteur (196 Ko, reutilise), puis placee de bas en haut (solides, puis torches/portes...) ;
 *  - le placement est limite par un budget de temps par tick (pas de nombre fixe) : le serveur ne gele jamais ;
 *  - les chunks sont charges au fil de l'eau, jamais tous d'un coup ;
 *  - l'undo est capture au moment de chaque colonne, sous forme de tableaux de char par section (2 octets/bloc) ;
 *  - effets reduits (quelques particules/sons par tick) et progression dans l'action bar.
 */
final class BigBuild {
    static final long BUDGET_NANOS = 20_000_000L;   // 20 ms de placement par tick
    static final int MAX_LINKS = 4_000_000;          // couples (operation, colonne) maximum
    static final int MAX_COLUMNS = 60_000;           // colonnes de chunks maximum (~3900 x 3900 blocs)

    /** Une colonne de chunk (16x16 blocs, toute la hauteur) et les operations qui la touchent, dans l'ordre. */
    static final class Col {
        final int cx, cz;
        int count;
        int[] ops;
        int fill;
        Col(int cx, int cz) { this.cx = cx; this.cz = cz; }
    }

    final int[] ops;          // operations en coordonnees MONDE (y non decoupe), 7 int chacune
    final BlockState[] pal;   // etats deja tournes
    final Col[] order;        // colonnes dans l'ordre de placement
    final int minY, maxY;
    final long estimate;

    private BigBuild(int[] ops, BlockState[] pal, Col[] order, int minY, int maxY, long estimate) {
        this.ops = ops; this.pal = pal; this.order = order; this.minY = minY; this.maxY = maxY; this.estimate = estimate;
    }

    // ---------- preparation (thread annexe, aucun acces au monde) ----------

    static BigBuild plan(Ops src, Rotation rot, int cx, int cz, BlockPos base, int minY, int maxY, long estimate) {
        int[] w = new int[src.n * Ops.STRIDE];
        int m = 0;
        for (int i = 0; i < src.n; i++) {
            int o = i * Ops.STRIDE;
            int[] a = Builds.rotate(rot, src.d[o] - cx, src.d[o + 2] - cz);
            int[] b = Builds.rotate(rot, src.d[o + 3] - cx, src.d[o + 5] - cz);
            int y0 = base.getY() + src.d[o + 1], y1 = base.getY() + src.d[o + 4];
            if (y1 < minY || y0 > maxY) continue;
            int p = m++ * Ops.STRIDE;
            w[p] = base.getX() + Math.min(a[0], b[0]);
            w[p + 1] = y0;
            w[p + 2] = base.getZ() + Math.min(a[1], b[1]);
            w[p + 3] = base.getX() + Math.max(a[0], b[0]);
            w[p + 4] = y1;
            w[p + 5] = base.getZ() + Math.max(a[1], b[1]);
            w[p + 6] = src.d[o + 6];
        }
        if (m == 0) throw new IllegalArgumentException("le build est entierement hors de la hauteur du monde");
        w = Arrays.copyOf(w, m * Ops.STRIDE);

        // Index (colonne -> operations), en deux passes pour ne jamais allouer de listes par colonne.
        long links = 0;
        for (int i = 0; i < m; i++) {
            int o = i * Ops.STRIDE;
            links += (long) ((w[o + 3] >> 4) - (w[o] >> 4) + 1) * ((w[o + 5] >> 4) - (w[o + 2] >> 4) + 1);
            if (links > MAX_LINKS) throw new IllegalArgumentException("build trop etendu (trop de zones a traiter)");
        }
        Map<Long, Col> cols = new HashMap<>();
        for (int i = 0; i < m; i++) {
            int o = i * Ops.STRIDE;
            for (int x = w[o] >> 4; x <= w[o + 3] >> 4; x++) {
                for (int z = w[o + 2] >> 4; z <= w[o + 5] >> 4; z++) {
                    long key = key(x, z);
                    Col c = cols.get(key);
                    if (c == null) { c = new Col(x, z); cols.put(key, c); }
                    c.count++;
                }
            }
            if (cols.size() > MAX_COLUMNS) throw new IllegalArgumentException("build trop etendu (> " + MAX_COLUMNS + " colonnes de chunks)");
        }
        for (Col c : cols.values()) c.ops = new int[c.count];
        for (int i = 0; i < m; i++) {
            int o = i * Ops.STRIDE;
            for (int x = w[o] >> 4; x <= w[o + 3] >> 4; x++) {
                for (int z = w[o + 2] >> 4; z <= w[o + 5] >> 4; z++) {
                    Col c = cols.get(key(x, z));
                    c.ops[c.fill++] = i;
                }
            }
        }

        BlockState[] pal = new BlockState[src.palette.size()];
        for (int i = 0; i < pal.length; i++) pal[i] = src.palette.get(i).rotate(rot);

        // Du centre vers l'exterieur : la construction "pousse" depuis le point clique.
        int bcx = base.getX() >> 4, bcz = base.getZ() >> 4;
        Col[] order = cols.values().toArray(new Col[0]);
        Arrays.sort(order, Comparator
                .comparingLong((Col c) -> (long) (c.cx - bcx) * (c.cx - bcx) + (long) (c.cz - bcz) * (c.cz - bcz))
                .thenComparingInt(c -> c.cx).thenComparingInt(c -> c.cz));
        return new BigBuild(w, pal, order, minY, maxY, estimate);
    }

    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

    // ---------- undo compact ----------

    /** Anciens blocs d'une colonne : un char[4096] par section touchee (0 = intact, sinon index de palette + 1). */
    static final class ColUndo {
        final int cx, cz;
        final char[][] secs;
        ColUndo(int cx, int cz, int nSections) { this.cx = cx; this.cz = cz; this.secs = new char[nSections][]; }
    }

    static final class Undo {
        final int minY, nSections;
        final List<BlockState> pal = new ArrayList<>();
        final Map<BlockState, Integer> idx = new HashMap<>();
        final List<ColUndo> cols = new ArrayList<>();

        Undo(int minY, int maxY) { this.minY = minY; this.nSections = ((maxY - minY) >> 4) + 1; }

        char code(BlockState s) {
            Integer i = idx.get(s);
            if (i == null) { i = pal.size(); pal.add(s); idx.put(s, i); }
            return (char) (i + 1);
        }

        void save(ColUndo c, int y, int inSection, BlockState old) {
            int rel = y - minY;
            char[] a = c.secs[rel >> 4];
            if (a == null) a = c.secs[rel >> 4] = new char[4096];
            a[((rel & 15) << 8) | inSection] = code(old);
        }
    }

    // ---------- placement ----------

    static final class Job implements Builds.Task {
        private final ServerLevel level;
        private final UUID owner;
        private final String name;
        private final BigBuild b;
        private final Undo undo;
        private final char[] buf;
        private final byte[] solid;   // par entree de palette : 0 inconnu, 1 solide, 2 autre
        private final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        private final Sink sink = new Sink();

        private int ci = 0;           // colonne en cours
        private Col cur;
        private ColUndo cu;
        private int lo, span, k;      // plage de y de la colonne (monde), taille d'un passage, curseur
        private long placed = 0;
        private long ticks = 0;
        private boolean hasLast;
        private int lx, ly, lz;

        Job(ServerLevel level, UUID owner, String name, BigBuild b, Undo undo) {
            this.level = level; this.owner = owner; this.name = name; this.b = b; this.undo = undo;
            this.buf = new char[(b.maxY - b.minY + 1) << 8];
            this.solid = new byte[b.pal.length];
        }

        /** Ecrit une ligne de la colonne courante dans le tampon. */
        private final class Sink implements Ops.Sink {
            char v;
            @Override public void run(int y, int z, int xa, int xb) {
                int i = ((y - b.minY) << 8) | ((z & 15) << 4);
                Arrays.fill(buf, i | (xa & 15), (i | (xb & 15)) + 1, v);
            }
        }

        private boolean isSolid(int pi) {
            byte s = solid[pi];
            if (s == 0) {
                s = b.pal[pi].getCollisionShape(level, mp).isEmpty() ? (byte) 2 : (byte) 1;
                solid[pi] = s;
            }
            return s == 1;
        }

        private void startColumn() {
            cur = b.order[ci];
            Arrays.fill(buf, (char) 0);
            int bx = cur.cx << 4, bz = cur.cz << 4;
            int ymin = Integer.MAX_VALUE, ymax = Integer.MIN_VALUE;
            for (int oi : cur.ops) {
                int o = oi * Ops.STRIDE;
                int meta = b.ops[o + 6];
                sink.v = (char) ((meta & 0xFFFFFF) + 1);
                Ops.rows(b.ops[o], b.ops[o + 1], b.ops[o + 2], b.ops[o + 3], b.ops[o + 4], b.ops[o + 5], meta >>> 24,
                        bx, bx + 15, b.minY, b.maxY, bz, bz + 15, sink);
                ymin = Math.min(ymin, Math.max(b.ops[o + 1], b.minY));
                ymax = Math.max(ymax, Math.min(b.ops[o + 4], b.maxY));
            }
            lo = ymin;
            span = (ymax - ymin + 1) << 8;
            k = 0;
            cu = new ColUndo(cur.cx, cur.cz, undo.nSections);
            undo.cols.add(cu);
        }

        /** Place des blocs de la colonne courante jusqu'a epuisement du budget. Renvoie vrai si la colonne est finie. */
        private boolean fillColumn(long t0) {
            int bx = cur.cx << 4, bz = cur.cz << 4;
            int start = (lo - b.minY) << 8;
            int total = span * 2;
            while (k < total) {
                int pass = k >= span ? 1 : 0;
                int r = pass == 0 ? k : k - span;
                k++;
                char v = buf[start + r];
                if (v == 0) continue;
                int pi = v - 1;
                int y = lo + (r >> 8);
                mp.set(bx + (r & 15), y, bz + ((r >> 4) & 15));
                if ((isSolid(pi) ? 0 : 1) != pass) continue;
                BlockState st = b.pal[pi];
                BlockState old = level.getBlockState(mp);
                if (old == st) continue;
                undo.save(cu, y, r & 255, old);
                level.setBlock(mp, st, Block.UPDATE_ALL);
                placed++;
                hasLast = true; lx = mp.getX(); ly = mp.getY(); lz = mp.getZ();
                if (System.nanoTime() - t0 > BUDGET_NANOS) return k >= total;
            }
            return true;
        }

        @Override public UUID owner() { return owner; }

        @Override
        public boolean step() {
            long t0 = System.nanoTime();
            long before = placed;
            ticks++;
            while (true) {
                if (ci >= b.order.length) { finish(); return true; }
                if (cur == null) startColumn();
                if (!fillColumn(t0)) break;
                cur = null;
                ci++;
                if (System.nanoTime() - t0 > BUDGET_NANOS) break;
            }
            if (hasLast && placed > before) {
                level.sendParticles(ParticleTypes.END_ROD, lx + .5, ly + .5, lz + .5, 12, 1.5, 1.5, 1.5, 0.02);
                if (ticks % 10 == 1) {
                    float prog = (float) ci / b.order.length;
                    level.playSound(null, new BlockPos(lx, ly, lz), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.5f, 0.5f + 1.5f * prog);
                }
            }
            if (ticks % 20 == 1) {
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
                if (p != null) {
                    int pct = (int) (100L * ci / b.order.length);
                    p.sendSystemMessage(IBuildMod.txt("[ibuild] \"" + name + "\" : " + pct + "% (" + placed + " blocs)"), true);
                }
            }
            return false;
        }

        private void finish() {
            if (hasLast) {
                level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, lx + .5, ly + 1.0, lz + .5, 40, 1.0, 1.0, 1.0, 0.3);
                level.playSound(null, new BlockPos(lx, ly, lz), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0f, 1.5f);
            }
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
            if (p != null) p.sendSystemMessage(IBuildMod.txt("[ibuild] \"" + name + "\" termine ! " + placed + " blocs places (/ibuild undo pour annuler)"));
        }
    }

    // ---------- annulation ----------

    static final class UndoJob implements Builds.Task {
        private final ServerLevel level;
        private final UUID owner;
        private final Undo undo;
        private final List<ColUndo> cols;
        private final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        private int ci;
        private int s;
        private int i = 4095;
        private long restored = 0;
        private long ticks = 0;
        private boolean hasLast;
        private int lx, ly, lz;

        UndoJob(ServerLevel level, UUID owner, Undo undo) {
            this.level = level; this.owner = owner; this.undo = undo;
            this.cols = new ArrayList<>(undo.cols);
            this.ci = cols.size() - 1;
            this.s = undo.nSections - 1;
        }

        @Override public UUID owner() { return owner; }

        @Override
        public boolean step() {
            long t0 = System.nanoTime();
            ticks++;
            // Dernieres colonnes d'abord, et de haut en bas dans chaque colonne.
            while (ci >= 0) {
                ColUndo c = cols.get(ci);
                int bx = c.cx << 4, bz = c.cz << 4;
                while (s >= 0) {
                    char[] a = c.secs[s];
                    if (a != null) {
                        while (i >= 0) {
                            int ii = i--;
                            char code = a[ii];
                            if (code == 0) continue;
                            mp.set(bx + (ii & 15), undo.minY + (s << 4) + (ii >> 8), bz + ((ii >> 4) & 15));
                            BlockState old = undo.pal.get(code - 1);
                            if (level.getBlockState(mp) == old) continue;
                            level.setBlock(mp, old, Block.UPDATE_ALL);
                            restored++;
                            hasLast = true; lx = mp.getX(); ly = mp.getY(); lz = mp.getZ();
                            if (System.nanoTime() - t0 > BUDGET_NANOS) { effects(); return false; }
                        }
                    }
                    s--;
                    i = 4095;
                }
                ci--;
                s = undo.nSections - 1;
                i = 4095;
            }
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
            if (p != null) p.sendSystemMessage(IBuildMod.txt("[ibuild] Annulation terminee (" + restored + " blocs)."));
            return true;
        }

        private void effects() {
            if (hasLast) {
                level.sendParticles(ParticleTypes.SMOKE, lx + .5, ly + .5, lz + .5, 12, 1.5, 1.5, 1.5, 0.02);
            }
            if (ticks % 20 == 1) {
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
                if (p != null) {
                    int total = Math.max(1, cols.size());
                    p.sendSystemMessage(IBuildMod.txt("[ibuild] Annulation : " + (100L * (total - 1 - ci) / total) + "%"), true);
                }
            }
        }
    }
}
