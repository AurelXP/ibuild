package com.ibuild;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
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

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class Builds {
    static final Path DIR = FabricLoader.getInstance().getGameDir().resolve("ibuilds");
    static final int MAX_BLOCKS = 300_000;
    static final int MAX_COORD = 512;
    static final int PER_TICK = 200;

    private static final Map<String, JsonObject> FILES = new TreeMap<>();
    private static final List<String> ERRORS = new ArrayList<>();
    private static final Map<UUID, String> SELECTED = new HashMap<>();
    private static final Map<UUID, Job> JOBS = new HashMap<>();
    private static final Map<UUID, List<Entry>> UNDO = new HashMap<>();

    record Entry(BlockPos pos, BlockState state) {}

    private Builds() {}

    static void init() {
        try { Files.createDirectories(DIR); } catch (IOException ignored) {}
        reload();
        ServerTickEvents.END_SERVER_TICK.register(server -> JOBS.values().removeIf(Job::step));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { JOBS.clear(); UNDO.clear(); });
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
                try (Reader r = Files.newBufferedReader(p)) {
                    FILES.put(name, JsonParser.parseReader(r).getAsJsonObject());
                } catch (Exception e) {
                    ERRORS.add(name + " : " + e.getMessage());
                }
            }
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

    static void place(ServerLevel level, ServerPlayer p, BlockPos base) {
        UUID id = p.getUUID();
        String name = selected(id);
        if (name == null) { p.sendSystemMessage(IBuildMod.txt("[ibuild] Aucun build. Dossier : " + DIR)); return; }
        if (JOBS.containsKey(id)) { p.sendSystemMessage(IBuildMod.txt("[ibuild] Un build est deja en cours (/ibuild undo pour annuler).")); return; }

        Map<Long, BlockState> cells;
        try {
            cells = parse(FILES.get(name), level.registryAccess().lookupOrThrow(Registries.BLOCK));
        } catch (Exception e) {
            p.sendSystemMessage(IBuildMod.txt("[ibuild] JSON invalide (" + name + ") : " + e.getMessage()));
            return;
        }
        if (cells.isEmpty()) { p.sendSystemMessage(IBuildMod.txt("[ibuild] Build vide.")); return; }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (long k : cells.keySet()) {
            BlockPos lp = BlockPos.of(k);
            minX = Math.min(minX, lp.getX()); maxX = Math.max(maxX, lp.getX());
            minZ = Math.min(minZ, lp.getZ()); maxZ = Math.max(maxZ, lp.getZ());
        }
        int cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;

        // La facade du build (cote +z / sud) est tournee vers le joueur.
        Direction d = p.getDirection();
        Rotation rot = switch (d) {
            case NORTH -> Rotation.NONE;
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            default -> Rotation.COUNTERCLOCKWISE_90;
        };

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
        level.sendParticles(ParticleTypes.ENCHANT, base.getX() + .5, base.getY() + 1.0, base.getZ() + .5, 60, 1.5, 1.0, 1.5, 0.5);
        level.playSound(null, base, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.0f, 1.2f);
    }

    static void undo(ServerPlayer p) {
        UUID id = p.getUUID();
        List<Entry> u = UNDO.remove(id);
        if (u == null) { p.sendSystemMessage(IBuildMod.txt("[ibuild] Rien a annuler.")); return; }
        JOBS.remove(id);
        List<Entry> list = new ArrayList<>(u);
        list.sort(Comparator.comparingInt((Entry en) -> en.pos().getY()).reversed());
        JOBS.put(id, new Job((ServerLevel) p.level(), id, "undo", list, true));
        p.sendSystemMessage(IBuildMod.txt("[ibuild] Annulation de " + list.size() + " blocs..."));
    }

    private static int[] rotate(Rotation r, int x, int z) {
        return switch (r) {
            case NONE -> new int[]{x, z};
            case CLOCKWISE_90 -> new int[]{-z, x};
            case CLOCKWISE_180 -> new int[]{-x, -z};
            case COUNTERCLOCKWISE_90 -> new int[]{z, -x};
        };
    }

    // ---------- parsing du JSON ----------

    static Map<Long, BlockState> parse(JsonObject root, HolderLookup<Block> lookup) throws Exception {
        JsonArray ops = root.getAsJsonArray("ops");
        if (ops == null) throw new IllegalArgumentException("champ \"ops\" manquant");
        Map<Long, BlockState> out = new LinkedHashMap<>();
        int idx = 0;
        for (JsonElement el : ops) {
            idx++;
            JsonObject o = el.getAsJsonObject();
            String type = o.get("type").getAsString();
            BlockState st = BlockStateParser.parseForBlock(lookup, o.get("block").getAsString(), false).blockState();
            switch (type) {
                case "set" -> {
                    int[] a = vec(o.getAsJsonArray("pos"), idx);
                    out.put(new BlockPos(a[0], a[1], a[2]).asLong(), st);
                }
                case "fill" -> {
                    int[] a = vec(o.getAsJsonArray("from"), idx);
                    int[] b = vec(o.getAsJsonArray("to"), idx);
                    String mode = o.has("mode") ? o.get("mode").getAsString() : "solid";
                    int x0 = Math.min(a[0], b[0]), x1 = Math.max(a[0], b[0]);
                    int y0 = Math.min(a[1], b[1]), y1 = Math.max(a[1], b[1]);
                    int z0 = Math.min(a[2], b[2]), z1 = Math.max(a[2], b[2]);
                    long vol = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
                    if (vol > MAX_BLOCKS) throw new IllegalArgumentException("op " + idx + " trop grosse (" + vol + " blocs)");
                    for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                        boolean ex = x == x0 || x == x1, ey = y == y0 || y == y1, ez = z == z0 || z == z1;
                        boolean ok = switch (mode) {
                            case "solid" -> true;
                            case "hollow" -> ex || ey || ez;
                            case "walls" -> ex || ez;
                            case "outline" -> (ex ? 1 : 0) + (ey ? 1 : 0) + (ez ? 1 : 0) >= 2;
                            default -> throw new IllegalArgumentException("op " + idx + " : mode inconnu " + mode);
                        };
                        if (ok) out.put(new BlockPos(x, y, z).asLong(), st);
                    }
                }
                default -> throw new IllegalArgumentException("op " + idx + " : type inconnu " + type);
            }
            if (out.size() > MAX_BLOCKS) throw new IllegalArgumentException("build trop grand (> " + MAX_BLOCKS + " blocs)");
        }
        return out;
    }

    private static int[] vec(JsonArray a, int idx) {
        if (a == null || a.size() != 3) throw new IllegalArgumentException("op " + idx + " : coordonnees [x,y,z] attendues");
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) {
            v[i] = a.get(i).getAsInt();
            if (Math.abs(v[i]) > MAX_COORD) throw new IllegalArgumentException("op " + idx + " : coordonnee hors limite (" + MAX_COORD + ")");
        }
        return v;
    }

    // ---------- placement progressif avec effets ----------

    private static final class Job {
        final ServerLevel level;
        final UUID owner;
        final String name;
        final List<Entry> list;
        final boolean undo;
        int i = 0;

        Job(ServerLevel level, UUID owner, String name, List<Entry> list, boolean undo) {
            this.level = level; this.owner = owner; this.name = name; this.list = list; this.undo = undo;
        }

        boolean step() {
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
