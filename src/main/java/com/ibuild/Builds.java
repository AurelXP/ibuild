package com.ibuild;

import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Builds {
    private static final Logger LOGGER = LogUtils.getLogger();

    static final Path DIR = FabricLoader.getInstance().getGameDir().resolve("ibuilds");
    /** Au-dessus de ce nombre de blocs, le build passe en mode optimise (voir BigBuild). */
    static final int BIG_THRESHOLD = 100_000;
    static final int PER_TICK = 1000;

    /** Fichiers valides : nom -> chemin. Les arbres JSON ne sont PAS gardes en memoire. */
    private static final Map<String, Path> FILES = new TreeMap<>();
    private record Checked(long size, long mtime, String error) {}
    private static final Map<Path, Checked> CHECKED = new HashMap<>();
    private static final List<String> ERRORS = new ArrayList<>();

    private static final Map<UUID, String> SELECTED = new HashMap<>();
    private static final Map<UUID, Task> JOBS = new HashMap<>();
    private static final Set<UUID> PENDING = new HashSet<>();
    private static final Map<UUID, List<Entry>> UNDO = new HashMap<>();
    private static final Map<UUID, BigBuild.Undo> BIG_UNDO = new HashMap<>();

    /** Lecture / preparation des gros builds hors du thread serveur. */
    private static final ExecutorService PLANNER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ibuild-planner");
        t.setDaemon(true);
        return t;
    });

    record Entry(BlockPos pos, BlockState state) {}

    /** Travail execute un peu a chaque tick serveur. */
    interface Task {
        /** Renvoie vrai quand le travail est termine. */
        boolean step();
        UUID owner();
    }

    private Builds() {}

    static void init() {
        try { Files.createDirectories(DIR); } catch (IOException ignored) {}
        reload();
        ServerTickEvents.END_SERVER_TICK.register(server -> JOBS.values().removeIf(job -> {
            try {
                return job.step();
            } catch (Throwable t) {
                // Une erreur ne doit jamais faire tomber le serveur : on arrete juste ce build.
                LOGGER.error("[ibuild] erreur pendant un build", t);
                ServerPlayer p = server.getPlayerList().getPlayer(job.owner());
                if (p != null) p.sendSystemMessage(IBuildMod.txt("[ibuild] Build interrompu : " + t));
                return true;
            }
        }));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            JOBS.clear(); UNDO.clear(); BIG_UNDO.clear(); PENDING.clear();
        });
    }

    // ---------- chargement des fichiers ----------

    static int reload() {
        FILES.clear();
        ERRORS.clear();
        try (var s = Files.list(DIR)) {
            List<Path> files = s.filter(p -> p.toString().toLowerCase().endsWith(".json")).sorted().toList();
            for (Path p : files) {
                String fn = p.getFileName().toString();
                String name = fn.substring(0, fn.length() - 5);
                try {
                    long size = Files.size(p), mtime = Files.getLastModifiedTime(p).toMillis();
                    Checked c = CHECKED.get(p);
                    if (c == null || c.size() != size || c.mtime() != mtime) {
                        // Verification de syntaxe en streaming, refaite seulement si le fichier a change.
                        c = new Checked(size, mtime, Ops.validate(p));
                        CHECKED.put(p, c);
                    }
                    if (c.error() == null) FILES.put(name, p);
                    else ERRORS.add(name + " : " + c.error());
                } catch (IOException e) {
                    ERRORS.add(name + " : " + e.getMessage());
                }
            }
            CHECKED.keySet().retainAll(new HashSet<>(files));
        } catch (IOException e) {
            ERRORS.add("Dossier illisible : " + e.getMessage());
        }
        return FILES.size();
    }

    static List<String> names() { return new ArrayList<>(FILES.keySet()); }

    private static String selected(UUID id) {
        String s = SELECTED.get(id);
        if (s != null && FILES.containsKey(s)) return s;
        return FILES.isEmpty() ? null : FILES.keySet().iterator().next();
    }

    // ---------- commandes / messages ----------

    static void reloadMsg(ServerPlayer p) {
        int n = reload();
        p.sendSystemMessage(IBuildMod.txt("[ibuild] " + n + " build(s) charge(s) depuis " + DIR));
        for (String e : ERRORS) p.sendSystemMessage(IBuildMod.txt("[ibuild] ERREUR " + e));
    }

    static void list(ServerPlayer p) {
        reload();
        if (FILES.isEmpty()) {
            p.sendSystemMessage(IBuildMod.txt("[ibuild] Aucun build. Mets tes .json dans : " + DIR));
        } else {
            String cur = selected(p.getUUID());
            for (String n : FILES.keySet()) p.sendSystemMessage(IBuildMod.txt((n.equals(cur) ? " > " : "   ") + n));
        }
        for (String e : ERRORS) p.sendSystemMessage(IBuildMod.txt("[ibuild] ERREUR " + e));
    }

    static void select(ServerPlayer p, String name) {
        if (!FILES.containsKey(name)) reload();
        if (!FILES.containsKey(name)) {
            p.sendSystemMessage(IBuildMod.txt("[ibuild] Build introuvable : " + name));
            return;
        }
        SELECTED.put(p.getUUID(), name);
        showSelected(p);
    }

    static void cycle(ServerPlayer p) {
        reload();
        List<String> n = names();
        if (n.isEmpty()) { showSelected(p); return; }
        int i = n.indexOf(selected(p.getUUID()));
        SELECTED.put(p.getUUID(), n.get((i + 1) % n.size()));
        showSelected(p);
        ((ServerLevel) p.level()).playSound(null, p.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 0.6f, 1.4f);
    }

    static void showSelected(ServerPlayer p) {
        String s = selected(p.getUUID());
        p.sendSystemMessage(IBuildMod.txt(s == null ? "Aucun build dans " + DIR : "Build : " + s), true);
    }

    // ---------- placement ----------

    /** Resultat de la preparation (thread annexe). */
    private record Prep(Ops ops, int cx, int cz, long estimate, BigBuild big) {}

    static void place(ServerLevel level, ServerPlayer p, BlockPos base) {
        UUID id = p.getUUID();
        String name = selected(id);
        if (name == null) { p.sendSystemMessage(IBuildMod.txt("[ibuild] Aucun build. Dossier : " + DIR)); return; }
        if (JOBS.containsKey(id) || PENDING.contains(id)) {
            p.sendSystemMessage(IBuildMod.txt("[ibuild] Un build est deja en cours (/ibuild undo pour annuler)."));
            return;
        }

        // Tout ce qui touche au monde / au joueur est lu ICI, sur le thread serveur.
        Path file = FILES.get(name);
        HolderLookup<Block> lookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        Rotation rot = rotationFor(p.getDirection());
        int minY = minY(level), maxY = maxY(level);
        var server = level.getServer();

        PENDING.add(id);
        PLANNER.execute(() -> {
            Prep prep = null;
            Throwable err = null;
            try {
                prep = prepare(file, lookup, rot, base, minY, maxY);
            } catch (OutOfMemoryError e) {
                err = new RuntimeException("memoire insuffisante (augmente -Xmx dans le lanceur)");
            } catch (Throwable e) {
                err = e;
            }
            Prep fp = prep;
            Throwable fe = err;
            server.execute(() -> finishPlace(level, id, name, base, rot, fp, fe));
        });
    }

    /** La facade du build (cote +z / sud) est tournee vers le joueur. */
    private static Rotation rotationFor(Direction d) {
        return switch (d) {
            case NORTH -> Rotation.NONE;
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            default -> Rotation.COUNTERCLOCKWISE_90;
        };
    }

    /** Thread annexe : lit le fichier en streaming, decide du mode, prepare le plan optimise. Aucun acces au monde. */
    private static Prep prepare(Path file, HolderLookup<Block> lookup, Rotation rot, BlockPos base, int minY, int maxY) throws IOException {
        Ops ops = Ops.read(file, lookup);
        if (ops.n == 0) return new Prep(ops, 0, 0, 0, null);

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int lowY = Integer.MAX_VALUE, highY = Integer.MIN_VALUE;
        for (int i = 0; i < ops.n; i++) {
            int o = i * Ops.STRIDE;
            minX = Math.min(minX, ops.d[o]);     maxX = Math.max(maxX, ops.d[o + 3]);
            lowY = Math.min(lowY, ops.d[o + 1]); highY = Math.max(highY, ops.d[o + 4]);
            minZ = Math.min(minZ, ops.d[o + 2]); maxZ = Math.max(maxZ, ops.d[o + 5]);
        }
        int cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
        long est = ops.count();

        // Le mode classique empaquette les positions dans un long (y sur 12 bits) : hors de cette plage -> mode optimise.
        boolean big = est > BIG_THRESHOLD || lowY < -2048 || highY > 2047;
        BigBuild plan = big ? BigBuild.plan(ops, rot, cx, cz, base, minY, maxY, est) : null;
        return new Prep(ops, cx, cz, est, plan);
    }

    /** Thread serveur : demarre le travail. */
    private static void finishPlace(ServerLevel level, UUID id, String name, BlockPos base, Rotation rot, Prep prep, Throwable err) {
        PENDING.remove(id);
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
        if (p == null) return;
        if (err != null) {
            p.sendSystemMessage(IBuildMod.txt("[ibuild] JSON invalide (" + name + ") : " + err.getMessage()));
            return;
        }
        if (prep.ops().n == 0) { p.sendSystemMessage(IBuildMod.txt("[ibuild] Build vide.")); return; }
        if (JOBS.containsKey(id)) { p.sendSystemMessage(IBuildMod.txt("[ibuild] Un build est deja en cours (/ibuild undo pour annuler).")); return; }

        UNDO.remove(id);
        BIG_UNDO.remove(id);
        if (prep.big() != null) startBig(level, p, name, base, prep);
        else startClassic(level, p, name, base, rot, prep);
        level.sendParticles(ParticleTypes.ENCHANT, base.getX() + .5, base.getY() + 1.0, base.getZ() + .5, 60, 1.5, 1.0, 1.5, 0.5);
        level.playSound(null, base, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.0f, 1.2f);
    }

    // ---------- mode optimise (> BIG_THRESHOLD blocs) ----------

    private static void startBig(ServerLevel level, ServerPlayer p, String name, BlockPos base, Prep prep) {
        UUID id = p.getUUID();
        BigBuild big = prep.big();
        BigBuild.Undo undo = new BigBuild.Undo(big.minY, big.maxY);
        BIG_UNDO.put(id, undo);
        JOBS.put(id, new BigBuild.Job(level, id, name, big, undo));
        p.sendSystemMessage(IBuildMod.txt("[ibuild] Construction de \"" + name + "\" en mode optimise : jusqu'a "
                + prep.estimate() + " blocs, " + big.order.length + " colonnes de chunks (progression dans la barre d'action)..."));
    }

    // ---------- mode classique (<= BIG_THRESHOLD blocs) ----------

    private static void startClassic(ServerLevel level, ServerPlayer p, String name, BlockPos base, Rotation rot, Prep prep) {
        UUID id = p.getUUID();
        Map<Long, BlockState> cells = expand(prep.ops());
        int cx = prep.cx(), cz = prep.cz();

        List<Entry> toPlace = new ArrayList<>();
        List<Entry> undo = new ArrayList<>();
        for (Map.Entry<Long, BlockState> e : cells.entrySet()) {
            BlockPos lp = BlockPos.of(e.getKey());
            int[] r = rotate(rot, lp.getX() - cx, lp.getZ() - cz);
            BlockPos wp = new BlockPos(base.getX() + r[0], base.getY() + lp.getY(), base.getZ() + r[1]);
            if (level.isOutsideBuildHeight(wp)) continue;
            toPlace.add(new Entry(wp, e.getValue().rotate(rot)));
            undo.add(new Entry(wp, level.getBlockState(wp)));
        }
        // Blocs solides d'abord (de bas en haut), puis torches/portes/etc.
        List<Entry> solid = new ArrayList<>(), other = new ArrayList<>();
        for (Entry en : toPlace) {
            (en.state().getCollisionShape(level, en.pos()).isEmpty() ? other : solid).add(en);
        }
        Comparator<Entry> byY = Comparator.comparingInt((Entry en) -> en.pos().getY());
        solid.sort(byY);
        other.sort(byY);
        toPlace = new ArrayList<>(solid);
        toPlace.addAll(other);

        UNDO.put(id, undo);
        JOBS.put(id, new Job(level, id, name, toPlace, false));
        p.sendSystemMessage(IBuildMod.txt("[ibuild] Construction de \"" + name + "\" : " + toPlace.size() + " blocs..."));
    }

    static void undo(ServerPlayer p) {
        UUID id = p.getUUID();
        BigBuild.Undo big = BIG_UNDO.remove(id);
        List<Entry> u = UNDO.remove(id);
        if (big == null && u == null) { p.sendSystemMessage(IBuildMod.txt("[ibuild] Rien a annuler.")); return; }
        JOBS.remove(id);
        ServerLevel level = (ServerLevel) p.level();
        if (big != null) {
            JOBS.put(id, new BigBuild.UndoJob(level, id, big));
            p.sendSystemMessage(IBuildMod.txt("[ibuild] Annulation en cours (mode optimise)..."));
        } else {
            List<Entry> list = new ArrayList<>(u);
            list.sort(Comparator.comparingInt((Entry en) -> en.pos().getY()).reversed());
            JOBS.put(id, new Job(level, id, "undo", list, true));
            p.sendSystemMessage(IBuildMod.txt("[ibuild] Annulation de " + list.size() + " blocs..."));
        }
    }

    static int[] rotate(Rotation r, int x, int z) {
        return switch (r) {
            case NONE -> new int[]{x, z};
            case CLOCKWISE_90 -> new int[]{-z, x};
            case CLOCKWISE_180 -> new int[]{-x, -z};
            case COUNTERCLOCKWISE_90 -> new int[]{z, -x};
        };
    }

    // ---------- hauteur du monde (sans dependre d'un nom d'API) ----------

    private static int minY(ServerLevel l) {
        int lo = -(1 << 16), hi = 0; // lo : hors du monde, hi : dans le monde
        while (hi - lo > 1) {
            int mid = (lo + hi) >> 1;
            if (l.isOutsideBuildHeight(new BlockPos(0, mid, 0))) lo = mid; else hi = mid;
        }
        return hi;
    }

    private static int maxY(ServerLevel l) {
        int lo = 0, hi = 1 << 16; // lo : dans le monde, hi : hors du monde
        while (hi - lo > 1) {
            int mid = (lo + hi) >> 1;
            if (l.isOutsideBuildHeight(new BlockPos(0, mid, 0))) hi = mid; else lo = mid;
        }
        return lo;
    }

    // ---------- expansion du mode classique ----------

    /** Memes cellules et meme ordre d'insertion qu'avant (le dernier ecrase les precedents). */
    private static Map<Long, BlockState> expand(Ops ops) {
        Map<Long, BlockState> out = new LinkedHashMap<>();
        for (int i = 0; i < ops.n; i++) {
            int o = i * Ops.STRIDE;
            int meta = ops.d[o + 6];
            BlockState st = ops.palette.get(meta & 0xFFFFFF);
            int mode = meta >>> 24;
            int x0 = ops.d[o], y0 = ops.d[o + 1], z0 = ops.d[o + 2], x1 = ops.d[o + 3], y1 = ops.d[o + 4], z1 = ops.d[o + 5];
            long vol = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
            if (vol <= 2_000_000) {
                for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                    boolean ex = x == x0 || x == x1, ey = y == y0 || y == y1, ez = z == z0 || z == z1;
                    boolean ok = switch (mode) {
                        case Ops.SOLID -> true;
                        case Ops.HOLLOW -> ex || ey || ez;
                        case Ops.WALLS -> ex || ez;
                        default -> (ex ? 1 : 0) + (ey ? 1 : 0) + (ez ? 1 : 0) >= 2;
                    };
                    if (ok) out.put(new BlockPos(x, y, z).asLong(), st);
                }
            } else {
                // Grande boite creuse : on ne parcourt que sa surface.
                Ops.rows(x0, y0, z0, x1, y1, z1, mode, x0, x1, y0, y1, z0, z1, (y, z, xa, xb) -> {
                    for (int x = xa; x <= xb; x++) out.put(new BlockPos(x, y, z).asLong(), st);
                });
            }
        }
        return out;
    }

    // ---------- placement progressif avec effets (mode classique) ----------

    private static final class Job implements Task {
        final ServerLevel level;
        final UUID owner;
        final String name;
        final List<Entry> list;
        final boolean undo;
        int i = 0;

        Job(ServerLevel level, UUID owner, String name, List<Entry> list, boolean undo) {
            this.level = level; this.owner = owner; this.name = name; this.list = list; this.undo = undo;
        }

        @Override public UUID owner() { return owner; }

        @Override
        public boolean step() {
            if (list.isEmpty()) return true;
            int end = Math.min(list.size(), i + PER_TICK);
            ParticleOptions fx = undo ? ParticleTypes.SMOKE : ParticleTypes.END_ROD;
            for (int k = i; k < end; k++) {
                Entry e = list.get(k);
                level.setBlock(e.pos(), e.state(), Block.UPDATE_ALL);
                if (k % 4 == 0 && !e.state().isAir()) {
                    level.sendParticles(fx, e.pos().getX() + .5, e.pos().getY() + .5, e.pos().getZ() + .5, 2, .3, .3, .3, 0.02);
                }
            }
            BlockPos last = list.get(end - 1).pos();
            float prog = (float) end / list.size();
            level.playSound(null, last, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.5f, 0.5f + 1.5f * prog);
            i = end;
            if (i < list.size()) return false;

            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, last.getX() + .5, last.getY() + 1.0, last.getZ() + .5, 40, 1.0, 1.0, 1.0, 0.3);
            level.playSound(null, last, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0f, 1.5f);
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
            if (p != null) p.sendSystemMessage(IBuildMod.txt("[ibuild] " + (undo ? "Annulation terminee." : "\"" + name + "\" termine ! (/ibuild undo pour annuler)")));
            return true;
        }
    }
}
