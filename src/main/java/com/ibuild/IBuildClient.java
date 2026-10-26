package com.ibuild;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Mode "client seul" : fonctionne sur un serveur qui n'a PAS le mod.
 * Le build (.json) est lu sur le PC du joueur et converti en commandes /fill et /setblock envoyees au serveur,
 * quelques-unes par tick. Il faut donc etre operateur (ou avoir les commandes) sur le serveur.
 *
 * Commandes : /ibuild ... (identiques au mode serveur). Elles ne s'activent que si le serveur n'a pas le mod ;
 * sinon la commande est envoyee au serveur comme d'habitude.
 * /ibc ... : memes sous-commandes, mais TOUJOURS disponibles (mode client force), au cas ou la detection se tromperait.
 * Stick : /ibuild stick met un "stick fantome" dans ta barre (visible seulement chez toi, le serveur voit un
 * emplacement vide). Clic droit sur un bloc = place le build, sneak + clic = build suivant.
 */
public final class IBuildClient implements ClientModInitializer {
    /** Undo disponible seulement si le build est assez petit pour garder l'etat precedent du monde. */
    private static final long UNDO_MAX_CELLS = 8_000_000;
    private static final int UNDO_MAX_RUNS = 1_000_000;

    private static String selected;
    private static int perTick = 4;
    private static long fillLimit = 32_768; // = gamerule commandModificationBlockLimit (defaut vanilla)
    private static boolean planning;
    private static Job job;
    private static Job undoJob;
    /** Vrai si le dernier build etait trop gros pour garder un undo (pour donner le bon message). */
    private static boolean undoTooBig;
    /** Nombre de paves du dernier build exclus de l'undo car hors des chunks charges chez le client. */
    private static int snapshotSkipped;
    /** Slot de la barre (0-8) ou le stick fantome est affiche, -1 si desactive. */
    private static int ghostSlot = -1;

    // ---------- chargement temporaire des chunks (/forceload) ----------
    // Le serveur refuse /fill et /setblock sur un chunk non charge ("That position is not loaded").
    // Le build est donc traite par LOTS de chunks : /forceload add sur le lot, envoi des commandes du lot,
    // verification (erreurs "not loaded" -> on attend et on renvoie le lot), puis /forceload remove.
    // Un chunk deja force par le serveur (chunk loader) n'est JAMAIS libere : on ne libere que ceux que NOUS avons marques.

    /** Nombre max de chunks forces en meme temps. */
    private static final int BATCH_CHUNKS = 32;
    private static final int REPLY_TIMEOUT = 100;  // ticks d'attente des reponses de /forceload add
    private static final int LOAD_TICKS = 20;      // attente de chargement apres /forceload add
    private static final int VERIFY_TICKS = 20;    // attente des erreurs eventuelles apres l'envoi d'un lot
    private static final int RETRY_WAIT = 40;      // attente (x numero d'essai) avant de renvoyer un lot
    private static final int MAX_RETRIES = 4;

    private static boolean useForceload = true;
    private static boolean staleChecked;
    /** Nombre d'erreurs "That position is not loaded" recues depuis le dernier envoi de lot. */
    private static int unloadedErrors;
    /** Chunks pour lesquels un /forceload add a ete envoye et dont on attend la reponse (ordre d'envoi). */
    private static final ArrayDeque<Long> pendingAdds = new ArrayDeque<>();
    /** Chunks que NOUS avons forces et pas encore liberes. */
    private static final LinkedHashSet<Long> owned = new LinkedHashSet<>();

    private static long chunkKey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }
    private static int keyX(long k) { return (int) (k >> 32); }
    private static int keyZ(long k) { return (int) k; }

    private static Path forcedFile() { return Builds.DIR.resolve("forced_chunks.txt"); }

    /** Memorise sur disque les chunks encore forces, pour pouvoir les liberer (/ibuild unforce) si on est deconnecte en plein build. */
    private static void persistForced() {
        try {
            Path f = forcedFile();
            if (owned.isEmpty()) { Files.deleteIfExists(f); return; }
            StringBuilder sb = new StringBuilder();
            for (long k : owned) sb.append(keyX(k)).append(' ').append(keyZ(k)).append('\n');
            Files.writeString(f, sb.toString());
        } catch (IOException ignored) {}
    }

    private static List<Long> readForced() {
        List<Long> out = new ArrayList<>();
        try {
            Path f = forcedFile();
            if (!Files.exists(f)) return out;
            for (String line : Files.readAllLines(f)) {
                String[] t = line.trim().split("\\s+");
                if (t.length == 2) out.add(chunkKey(Integer.parseInt(t[0]), Integer.parseInt(t[1])));
            }
        } catch (IOException | NumberFormatException ignored) {}
        return out;
    }

    private static String forceCmd(String verb, long k) {
        return "forceload " + verb + " " + (keyX(k) * 16) + " " + (keyZ(k) * 16);
    }

    /** Libere tout ce qu'on a force (build termine, arrete, remplace ou en erreur). Si on est deconnecte, le fichier sur disque garde la trace. */
    private static void cleanupForced() {
        pendingAdds.clear();
        if (owned.isEmpty()) return;
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null) return;
        for (long k : owned) conn.sendCommand(forceCmd("remove", k));
        owned.clear();
        persistForced();
    }

    /** Reponses du serveur : erreurs "not loaded" et resultats de /forceload add (pour savoir quels chunks sont a nous). */
    private static void onGameMessage(Component m, boolean overlay) {
        if (overlay || job == null) return;
        if (!(m.getContents() instanceof TranslatableContents tc)) return;
        switch (tc.getKey()) {
            case "argument.pos.unloaded" -> unloadedErrors++;
            case "commands.forceload.added.single", "commands.forceload.added.multiple" -> addReply(true);
            case "commands.forceload.added.failure" -> addReply(false); // deja force par le serveur : on n'y touche pas
            default -> {}
        }
    }

    private static void addReply(boolean ours) {
        Long k = pendingAdds.poll();
        if (k != null && ours) owned.add(k);
    }

    /** Liste de paves : 7 int par pave (x0 y0 z0 x1 y1 z1 palette), coordonnees MONDE. Chaque pave tient dans UNE colonne de chunk. */
    private static final class Job {
        static final int P_START = 0, P_FORCE = 1, P_REPLIES = 2, P_LOAD = 3, P_SEND = 4, P_VERIFY = 5, P_RETRY = 6, P_UNFORCE = 7;

        final String name;
        final int[] pc;
        final int n;
        final String[] pal;

        /** Colonnes de chunk distinctes (dans l'ordre de traitement) et, pour chacune, les indices de ses paves (ordre du JSON). */
        final int[] chunkCx, chunkCz;
        final int[][] chunkPieces;

        int phase = P_START;
        int bs = 0, be = 0;          // lot courant : chunks [bs, be)
        int wait = 0, retries = 0;
        boolean forceAll = false;    // apres une erreur, on force aussi les chunks que le client croit charges
        int[] order = new int[0];    // paves du lot courant
        int cur = 0;
        long sent = 0;               // paves envoyes (progression)
        int failedBatches = 0;
        int ticks = 0;
        final List<String> cmds = new ArrayList<>();
        int ci = 0;

        Job(String name, int[] pc, int n, String[] pal) {
            this.name = name; this.pc = pc; this.n = n; this.pal = pal;

            Map<Long, int[]> g = new HashMap<>();
            for (int k = 0; k < n; k++) {
                long key = chunkKey(pc[k * 7] >> 4, pc[k * 7 + 2] >> 4);
                int[] a = g.get(key);
                if (a == null) a = new int[5];
                if (a[0] + 1 >= a.length) a = Arrays.copyOf(a, a.length * 2);
                a[++a[0]] = k;
                g.put(key, a);
            }
            List<Long> keys = new ArrayList<>(g.keySet());
            keys.sort(Comparator.<Long>comparingInt(kk -> keyZ(kk)).thenComparingInt(kk -> keyX(kk)));
            chunkCx = new int[keys.size()];
            chunkCz = new int[keys.size()];
            chunkPieces = new int[keys.size()][];
            for (int c = 0; c < keys.size(); c++) {
                long key = keys.get(c);
                chunkCx[c] = keyX(key);
                chunkCz[c] = keyZ(key);
                int[] a = g.get(key);
                chunkPieces[c] = Arrays.copyOfRange(a, 1, 1 + a[0]);
            }
        }

        int chunkCount() { return chunkCx.length; }
    }

    private record Plan(int[] pc, int n, String[] pal, long cells, int extent) {}

    private static final class IntBuf {
        int[] a = new int[7 * 256];
        int size = 0;
        void add7(int v0, int v1, int v2, int v3, int v4, int v5, int v6) {
            if (size + 7 > a.length) a = Arrays.copyOf(a, a.length * 2);
            a[size++] = v0; a[size++] = v1; a[size++] = v2; a[size++] = v3; a[size++] = v4; a[size++] = v5; a[size++] = v6;
        }
        int[] trimmed() { return Arrays.copyOf(a, size); }
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(IBuildClient::tick);
        ClientReceiveMessageEvents.GAME.register(IBuildClient::onGameMessage);

        // FAIL (et non SUCCESS) : le serveur ne recoit aucun paquet, donc pas d'ouverture de coffre/porte par erreur.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(level instanceof ClientLevel) || hand != InteractionHand.MAIN_HAND || !clientMode()) return InteractionResult.PASS;
            if (!isGhost(player.getMainHandItem())) return InteractionResult.PASS;
            if (player.isShiftKeyDown()) cycle();
            else place(hit.getBlockPos().relative(hit.getDirection()));
            return InteractionResult.FAIL;
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(level instanceof ClientLevel) || hand != InteractionHand.MAIN_HAND || !clientMode()) return InteractionResult.PASS;
            if (!isGhost(player.getMainHandItem())) return InteractionResult.PASS;
            if (player.isShiftKeyDown()) cycle();
            else { String n = selected(); bar(n == null ? "Aucun build dans " + Builds.DIR : "Build : " + n); }
            return InteractionResult.FAIL;
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // Deux racines avec exactement les memes sous-commandes :
            //  - /ibuild : cedee au serveur si celui-ci a le mod (c'est alors le serveur qui gere /ibuild) ;
            //  - /ibc    : TOUJOURS disponible, mode client force. Sert de secours si la detection du mod
            //              cote serveur se trompe (voir /ibc status).
            for (String root : new String[]{"ibuild", "ibc"}) {
                var cmd = ClientCommands.literal(root);
                if (root.equals("ibuild")) cmd.requires(src -> !serverHasMod());

                cmd.executes(c -> { help(); return 1; });
                cmd.then(ClientCommands.literal("help").executes(c -> { help(); return 1; }));
                cmd.then(ClientCommands.literal("status").executes(c -> { status(); return 1; }));
                cmd.then(ClientCommands.literal("list").executes(c -> { list(); return 1; }));
                cmd.then(ClientCommands.literal("reload").executes(c -> { msg(names().size() + " build(s) dans " + Builds.DIR); return 1; }));
                cmd.then(ClientCommands.literal("stick").executes(c -> { toggleGhost(); return 1; }));
                cmd.then(ClientCommands.literal("place").executes(c -> { placeAtCrosshair(); return 1; }));
                cmd.then(ClientCommands.literal("undo").executes(c -> { undo(); return 1; }));
                cmd.then(ClientCommands.literal("stop").executes(c -> { stop(); return 1; }));
                cmd.then(ClientCommands.literal("select").then(ClientCommands.argument("name", StringArgumentType.greedyString())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(names(), b))
                        .executes(c -> { select(StringArgumentType.getString(c, "name")); return 1; })));
                cmd.then(ClientCommands.literal("speed").then(ClientCommands.argument("n", IntegerArgumentType.integer(1, 50))
                        .executes(c -> {
                            perTick = IntegerArgumentType.getInteger(c, "n");
                            msg("Vitesse : " + perTick + " commande(s) par tick.");
                            return 1;
                        })));
                cmd.then(ClientCommands.literal("unforce").executes(c -> { unforce(); return 1; }));
                cmd.then(ClientCommands.literal("forceload").then(ClientCommands.argument("on", BoolArgumentType.bool())
                        .executes(c -> {
                            useForceload = BoolArgumentType.getBool(c, "on");
                            msg("Chargement temporaire des chunks (/forceload) : " + (useForceload ? "active" : "desactive") + ".");
                            return 1;
                        })));
                cmd.then(ClientCommands.literal("limit").then(ClientCommands.argument("n", IntegerArgumentType.integer(1, 2_000_000))
                        .executes(c -> {
                            fillLimit = IntegerArgumentType.getInteger(c, "n");
                            msg("Taille max d'un /fill : " + fillLimit + " blocs.");
                            return 1;
                        })));

                dispatcher.register(cmd);
            }
        });
    }

    // ---------- detection du mod cote serveur ----------

    /** Vrai si ce serveur a le mod (solo / LAN, ou serveur qui a declare IBuildPresence). */
    private static boolean serverHasMod() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc.getSingleplayerServer() != null || ClientPlayNetworking.canSend(IBuildPresence.TYPE);
        } catch (Throwable t) {
            // En cas de doute (reseau pas pret, API qui leve une exception...), on considere que le serveur n'a PAS le mod :
            // mieux vaut des commandes client disponibles que des commandes qui disparaissent.
            return false;
        }
    }

    /** Diagnostic : /ibuild status ou /ibc status. */
    private static void status() {
        Minecraft mc = Minecraft.getInstance();
        boolean solo = mc.getSingleplayerServer() != null;
        boolean presence = false;
        try { presence = ClientPlayNetworking.canSend(IBuildPresence.TYPE); } catch (Throwable ignored) {}
        msg("Serveur avec le mod : " + serverHasMod() + " (solo/LAN : " + solo + ", paquet de presence : " + presence + ")");
        msg("Mode client actif : " + clientMode());
        msg("Si le serveur n'a pas le mod mais que /ibuild est absent, utilise /ibc (meme commandes, toujours disponible).");
    }

    /** Le mode client n'est actif que connecte a un serveur SANS le mod. */
    private static boolean clientMode() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null && mc.getConnection() != null && !serverHasMod();
    }

    // ---------- stick fantome : present seulement chez le client, le serveur voit un emplacement vide ----------

    private static boolean isGhost(ItemStack st) {
        return !st.isEmpty() && st.is(IBuildMod.BUILDER_STICK);
    }

    private static void toggleGhost() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var inv = mc.player.getInventory();
        if (ghostSlot >= 0) {
            if (isGhost(inv.getItem(ghostSlot))) inv.setItem(ghostSlot, ItemStack.EMPTY);
            ghostSlot = -1;
            msg("Stick fantome retire.");
            return;
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                ghostSlot = i;
                inv.setItem(i, new ItemStack(IBuildMod.BUILDER_STICK));
                msg("Stick place dans l'emplacement " + (i + 1) + " de ta barre (invisible pour le serveur).");
                return;
            }
        }
        msg("Libere un emplacement de ta barre d'objets d'abord.");
    }

    /** Chaque tick : remet le stick s'il a ete efface, le retire pendant les inventaires (il ne doit jamais partir dans un paquet). */
    private static void tickGhost(Minecraft mc) {
        if (ghostSlot < 0) return;
        if (mc.player == null || !clientMode()) { ghostSlot = -1; return; }
        var inv = mc.player.getInventory();
        ItemStack cur = inv.getItem(ghostSlot);
        if (!mc.mouseHandler.isMouseGrabbed()) {
            if (isGhost(cur)) inv.setItem(ghostSlot, ItemStack.EMPTY);
            return;
        }
        if (cur.isEmpty()) inv.setItem(ghostSlot, new ItemStack(IBuildMod.BUILDER_STICK));
        else if (!isGhost(cur)) { ghostSlot = -1; msg("Emplacement occupe : stick fantome desactive. Refais /ibuild stick."); }
    }

    // ---------- messages ----------

    private static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[ibuild] " + s));
    }

    private static void bar(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.hud.setOverlayMessage(Component.literal(s), false);
    }

    private static void help() {
        msg("Mode client (serveur sans le mod) : il faut etre OP sur le serveur.");
        msg("/ibuild stick : stick fantome. Clic droit sur un bloc = place le build, sneak + clic = build suivant.");
        msg("/ibuild list | reload | select <nom> | place | undo | stop | speed <1-50> | limit <taille> | status");
        msg("/ibuild forceload <true|false> : charger temporairement les chunks eloignes (defaut : true). /ibuild unforce : liberer des chunks restes forces.");
        msg("/ibc fait exactement la meme chose et est toujours disponible (secours si /ibuild n'apparait pas).");
        msg("Dossier des builds : " + Builds.DIR);
    }

    // ---------- selection ----------

    private static List<String> names() {
        try (var s = Files.list(Builds.DIR)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(f -> f.toLowerCase().endsWith(".json"))
                    .map(f -> f.substring(0, f.length() - 5))
                    .sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String selected() {
        List<String> n = names();
        if (selected != null && n.contains(selected)) return selected;
        return n.isEmpty() ? null : n.get(0);
    }

    private static void list() {
        List<String> n = names();
        if (n.isEmpty()) { msg("Aucun build. Mets tes .json dans : " + Builds.DIR); return; }
        String cur = selected();
        for (String s : n) msg((s.equals(cur) ? " > " : "   ") + s);
    }

    private static void select(String name) {
        if (!names().contains(name)) { msg("Build introuvable : " + name); return; }
        selected = name;
        bar("Build : " + name);
    }

    private static void cycle() {
        List<String> n = names();
        if (n.isEmpty()) { bar("Aucun build dans " + Builds.DIR); return; }
        int i = n.indexOf(selected());
        selected = n.get((i + 1) % n.size());
        bar("Build : " + selected);
    }

    // ---------- placement ----------

    private static void placeAtCrosshair() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hitResult instanceof BlockHitResult h && h.getType() != HitResult.Type.MISS) {
            place(h.getBlockPos().relative(h.getDirection()));
        } else {
            msg("Vise un bloc.");
        }
    }

    private static void place(BlockPos base) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        if (job != null || planning) { msg("Un build est deja en cours (/ibuild stop pour l'arreter)."); return; }
        String name = selected();
        if (name == null) { msg("Aucun build. Mets tes .json dans : " + Builds.DIR); return; }

        ClientLevel level = mc.level;
        Path file = Builds.DIR.resolve(name + ".json");
        HolderLookup<Block> lookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        Rotation rot = rotationFor(mc.player.getDirection());
        int minY = minY(level), maxY = maxY(level);
        long limit = fillLimit;

        planning = true;
        msg("Preparation de \"" + name + "\"...");
        CompletableFuture.supplyAsync(() -> {
            try {
                return plan(file, lookup, rot, base, minY, maxY, limit);
            } catch (Throwable t) {
                throw new CompletionException(t);
            }
        }).whenComplete((p, err) -> mc.execute(() -> finishPlan(level, name, p, err)));
    }

    /** Meme regle que le mode serveur : la facade du build (cote +z / sud) est tournee vers le joueur. */
    private static Rotation rotationFor(Direction d) {
        return switch (d) {
            case NORTH -> Rotation.NONE;
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            default -> Rotation.COUNTERCLOCKWISE_90;
        };
    }

    /** Thread annexe : lit le JSON, tourne, decoupe en commandes. Aucun acces au monde. */
    private static Plan plan(Path file, HolderLookup<Block> lookup, Rotation rot, BlockPos base,
                             int minY, int maxY, long limit) throws IOException {
        Ops ops = Ops.read(file, lookup);
        if (ops.n == 0) return new Plan(new int[0], 0, new String[0], 0, 0);

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < ops.n; i++) {
            int o = i * Ops.STRIDE;
            minX = Math.min(minX, ops.d[o]);     maxX = Math.max(maxX, ops.d[o + 3]);
            minZ = Math.min(minZ, ops.d[o + 2]); maxZ = Math.max(maxZ, ops.d[o + 5]);
        }
        final int cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;

        String[] pal = new String[ops.palette.size()];
        for (int i = 0; i < pal.length; i++) pal[i] = ser(ops.palette.get(i).rotate(rot));

        IntBuf out = new IntBuf();
        long[] cells = {0};
        for (int i = 0; i < ops.n; i++) {
            int o = i * Ops.STRIDE;
            final int meta = ops.d[o + 6];
            final int mode = meta >>> 24, pi = meta & 0xFFFFFF;
            Boxes.decompose(ops.d[o], ops.d[o + 1], ops.d[o + 2], ops.d[o + 3], ops.d[o + 4], ops.d[o + 5], mode,
                    (x0, y0, z0, x1, y1, z1) -> {
                        int[] a = Builds.rotate(rot, x0 - cx, z0 - cz);
                        int[] b = Builds.rotate(rot, x1 - cx, z1 - cz);
                        int wy0 = Math.max(base.getY() + y0, minY), wy1 = Math.min(base.getY() + y1, maxY);
                        if (wy0 > wy1) return;
                        // D'abord aux frontieres de chunks (chaque morceau dans UNE colonne de chunk, pour le chargement
                        // temporaire par lots), puis en morceaux de volume <= limite d'un /fill.
                        Boxes.splitChunks(base.getX() + Math.min(a[0], b[0]), wy0, base.getZ() + Math.min(a[1], b[1]),
                                base.getX() + Math.max(a[0], b[0]), wy1, base.getZ() + Math.max(a[1], b[1]),
                                (qx0, qy0, qz0, qx1, qy1, qz1) -> Boxes.split(qx0, qy0, qz0, qx1, qy1, qz1,
                                        limit, (px0, py0, pz0, px1, py1, pz1) -> {
                                            out.add7(px0, py0, pz0, px1, py1, pz1, pi);
                                            cells[0] += (long) (px1 - px0 + 1) * (py1 - py0 + 1) * (pz1 - pz0 + 1);
                                        }));
                    });
        }
        return new Plan(out.trimmed(), out.size / 7, pal, cells[0], Math.max(maxX - minX, maxZ - minZ) + 1);
    }

    /** Thread client : demarre l'envoi des commandes. */
    private static void finishPlan(ClientLevel level, String name, Plan p, Throwable err) {
        planning = false;
        Minecraft mc = Minecraft.getInstance();
        if (err != null) {
            Throwable t = (err instanceof CompletionException && err.getCause() != null) ? err.getCause() : err;
            msg("JSON invalide (" + name + ") : " + t.getMessage());
            return;
        }
        if (mc.player == null || mc.level != level || mc.getConnection() == null) return;
        if (p.n() == 0) { msg("Build vide."); return; }

        undoJob = snapshot(level, p);
        undoTooBig = undoJob == null;
        job = new Job(name, p.pc(), p.n(), p.pal());
        double secs = p.n() / (perTick * 20.0);
        msg("Construction de \"" + name + "\" : " + p.n() + " commandes (~" + Math.round(secs) + " s).");
        if (undoJob == null) msg("ATTENTION : pas d'annulation possible pour ce build (trop gros : limite " + UNDO_MAX_CELLS + " blocs / "
                + UNDO_MAX_RUNS + " segments, ou hors de ta zone chargee). Seul /ibuild stop sera possible.");
        if (job.chunkCount() > 1 || p.extent() > 100) {
            msg(job.chunkCount() + " chunk(s) concerne(s)."
                    + (useForceload ? " Les chunks non charges seront charges temporairement avec /forceload (OP requis), puis liberes."
                                    : " /forceload est desactive : les zones non chargees par le serveur echoueront."));
        }
        if (snapshotSkipped > 0) {
            msg("Annulation partielle : " + snapshotSkipped + " morceau(x) hors de ta zone chargee ne pourront pas etre annules.");
        }
    }

    // ---------- envoi des commandes ----------

    private static void tick(Minecraft mc) {
        tickGhost(mc);
        if (mc.player == null) staleChecked = false;
        else if (!staleChecked && mc.level != null) {
            staleChecked = true;
            int stale = readForced().size();
            if (stale > 0 && job == null && owned.isEmpty() && clientMode()) {
                msg(stale + " chunk(s) forces par un build precedent n'ont peut-etre pas ete liberes (deconnexion en plein build ?). "
                        + "Si tu es sur le meme serveur : /ibuild unforce");
            }
        }
        Job j = job;
        if (j == null) return;
        if (mc.player == null || mc.getConnection() == null) {
            // Deconnecte : impossible de liberer maintenant, le fichier forced_chunks.txt garde la trace (/ibuild unforce au retour).
            job = null;
            persistForced();
            return;
        }
        try {
            step(mc, j);
        } catch (Throwable t) {
            job = null;
            cleanupForced();
            msg("Build interrompu : " + t);
        }
    }

    /** Envoie jusqu'a perTick commandes de la file j.cmds. Renvoie vrai quand la file est vide. */
    private static boolean sendQueued(Minecraft mc, Job j) {
        int end = Math.min(j.cmds.size(), j.ci + perTick);
        for (int k = j.ci; k < end; k++) mc.getConnection().sendCommand(j.cmds.get(k));
        j.ci = end;
        return j.ci >= j.cmds.size();
    }

    private static void step(Minecraft mc, Job j) {
        switch (j.phase) {
            case Job.P_START -> {
                if (j.bs >= j.chunkCount()) { finishJob(j); return; }
                j.be = Math.min(j.bs + BATCH_CHUNKS, j.chunkCount());
                j.retries = 0;
                j.forceAll = false;
                beginForce(mc, j);
            }
            case Job.P_FORCE -> {
                if (sendQueued(mc, j)) { j.phase = Job.P_REPLIES; j.wait = REPLY_TIMEOUT; }
            }
            case Job.P_REPLIES -> {
                if (pendingAdds.isEmpty()) {
                    persistForced();
                    j.phase = Job.P_LOAD; j.wait = LOAD_TICKS;
                } else if (--j.wait <= 0) {
                    // Aucune reponse lisible : /forceload refuse (pas OP ?) ou reponses non reconnues. On suppose que tout est a nous
                    // pour etre sur de tout liberer ensuite (un /forceload remove refuse est sans effet).
                    msg("Pas de reponse a /forceload (il faut etre OP). On continue quand meme.");
                    owned.addAll(pendingAdds);
                    pendingAdds.clear();
                    persistForced();
                    j.phase = Job.P_LOAD; j.wait = LOAD_TICKS;
                }
            }
            case Job.P_LOAD -> { if (--j.wait <= 0) startSend(j); }
            case Job.P_SEND -> {
                int end = Math.min(j.order.length, j.cur + perTick);
                for (int k = j.cur; k < end; k++) mc.getConnection().sendCommand(command(j, j.order[k]));
                j.sent += end - j.cur;
                j.cur = end;
                if (j.cur >= j.order.length) {
                    j.phase = Job.P_VERIFY; j.wait = VERIFY_TICKS;
                } else if (++j.ticks % 10 == 0) {
                    bar(j.name + " : " + Math.min(99L, 100L * j.sent / j.n) + " %");
                }
            }
            case Job.P_VERIFY -> {
                if (--j.wait > 0) return;
                if (unloadedErrors > 0 && useForceload && j.retries < MAX_RETRIES) {
                    j.retries++;
                    j.forceAll = true;
                    j.phase = Job.P_RETRY; j.wait = RETRY_WAIT * j.retries;
                } else {
                    if (unloadedErrors > 0) j.failedBatches++;
                    startUnforce(j);
                }
            }
            case Job.P_RETRY -> { if (--j.wait <= 0) beginForce(mc, j); }
            case Job.P_UNFORCE -> {
                if (sendQueued(mc, j)) { persistForced(); j.bs = j.be; j.phase = Job.P_START; }
            }
            default -> {}
        }
    }

    /** Prepare le /forceload du lot courant : seulement les chunks que le client ne voit pas (le serveur les a donc peut-etre decharges). */
    private static void beginForce(Minecraft mc, Job j) {
        j.cmds.clear();
        j.ci = 0;
        pendingAdds.clear();
        if (useForceload && mc.level != null) {
            for (int c = j.bs; c < j.be; c++) {
                long key = chunkKey(j.chunkCx[c], j.chunkCz[c]);
                if (owned.contains(key)) continue;
                boolean clientHasIt = mc.level.hasChunkAt(new BlockPos(j.chunkCx[c] * 16, 0, j.chunkCz[c] * 16));
                if (clientHasIt && !j.forceAll) continue; // visible chez nous => charge aussi sur le serveur
                j.cmds.add(forceCmd("add", key));
                pendingAdds.add(key);
            }
        }
        if (j.cmds.isEmpty()) { startSend(j); return; }
        j.phase = Job.P_FORCE;
    }

    private static void startSend(Job j) {
        int total = 0;
        for (int c = j.bs; c < j.be; c++) total += j.chunkPieces[c].length;
        int[] order = new int[total];
        int p = 0;
        for (int c = j.bs; c < j.be; c++) {
            System.arraycopy(j.chunkPieces[c], 0, order, p, j.chunkPieces[c].length);
            p += j.chunkPieces[c].length;
        }
        j.order = order;
        j.cur = 0;
        unloadedErrors = 0;
        j.phase = Job.P_SEND;
    }

    /** Libere les chunks du lot qu'on a forces nous-memes. */
    private static void startUnforce(Job j) {
        j.cmds.clear();
        j.ci = 0;
        for (int c = j.bs; c < j.be; c++) {
            long key = chunkKey(j.chunkCx[c], j.chunkCz[c]);
            if (owned.remove(key)) j.cmds.add(forceCmd("remove", key));
        }
        j.phase = Job.P_UNFORCE;
    }

    private static void finishJob(Job j) {
        job = null;
        cleanupForced();
        if (j.name.equals("undo")) msg("Annulation terminee.");
        else msg("\"" + j.name + "\" termine. (/ibuild undo pour annuler)");
        if (j.failedBatches > 0) {
            msg("ATTENTION : " + j.failedBatches + " groupe(s) de chunks n'ont pas pu etre charges par le serveur, une partie manque. "
                    + "Verifie que tu es OP (/forceload), puis recommence (ou /ibuild undo).");
        }
    }

    private static String command(Job j, int k) {
        int o = k * 7;
        int[] c = j.pc;
        String block = j.pal[c[o + 6]];
        if (c[o] == c[o + 3] && c[o + 1] == c[o + 4] && c[o + 2] == c[o + 5]) {
            return "setblock " + c[o] + " " + c[o + 1] + " " + c[o + 2] + " " + block;
        }
        return "fill " + c[o] + " " + c[o + 1] + " " + c[o + 2] + " " + c[o + 3] + " " + c[o + 4] + " " + c[o + 5] + " " + block;
    }

    private static void stop() {
        if (job == null) { msg("Aucun build en cours."); return; }
        job = null;
        cleanupForced();
        msg("Build arrete (les blocs deja places restent ; /ibuild undo pour tout annuler).");
    }

    private static void undo() {
        if (planning) { msg("Preparation en cours, reessaie dans un instant."); return; }
        if (undoJob == null) {
            msg(undoTooBig ? "Annulation impossible : le dernier build etait trop gros, ou hors de la zone chargee chez toi."
                           : "Rien a annuler.");
            return;
        }
        if (job != null) cleanupForced(); // on remplace un build en cours : on libere ses chunks d'abord
        job = undoJob;
        undoJob = null;
        msg("Annulation en cours (" + job.n + " commandes)...");
    }

    /** /ibuild unforce : libere les chunks forces par ibuild qui seraient restes (erreur, deconnexion en plein build). */
    private static void unforce() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        if (job != null || planning) { msg("Un build est en cours : /ibuild stop d'abord."); return; }
        LinkedHashSet<Long> all = new LinkedHashSet<>(owned);
        all.addAll(readForced());
        if (all.isEmpty()) { msg("Aucun chunk a liberer."); return; }
        for (long k : all) mc.getConnection().sendCommand(forceCmd("remove", k));
        owned.clear();
        persistForced();
        msg(all.size() + " chunk(s) liberes (/forceload remove envoye).");
    }

    // ---------- undo : etat precedent du monde, lu cote client (chunks charges chez le joueur) ----------

    private static Job snapshot(ClientLevel level, Plan p) {
        snapshotSkipped = 0;
        if (p.cells() > UNDO_MAX_CELLS) return null;
        Map<BlockState, Integer> idx = new HashMap<>();
        List<String> pal = new ArrayList<>();
        IntBuf runs = new IntBuf();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int k = 0; k < p.n(); k++) {
            int o = k * 7;
            int[] c = p.pc();
            // Un pave tient dans une seule colonne de chunk. S'il n'est pas charge chez nous, son etat precedent est inconnu
            // (lu comme de l'air) : on ne le restaure pas, plutot que de remplacer du terrain par de l'air a l'annulation.
            if (!level.hasChunkAt(mp.set(c[o], c[o + 1], c[o + 2]))) { snapshotSkipped++; continue; }
            for (int y = c[o + 1]; y <= c[o + 4]; y++) {
                for (int z = c[o + 2]; z <= c[o + 5]; z++) {
                    int runStart = 0, runIdx = -1;
                    for (int x = c[o]; x <= c[o + 3]; x++) {
                        BlockState st = level.getBlockState(mp.set(x, y, z));
                        Integer boxed = idx.get(st);
                        if (boxed == null) {
                            boxed = pal.size();
                            pal.add(ser(st));
                            idx.put(st, boxed);
                        }
                        int pi = boxed;
                        if (pi == runIdx) continue;
                        if (runIdx >= 0) runs.add7(runStart, y, z, x - 1, y, z, runIdx);
                        runStart = x;
                        runIdx = pi;
                    }
                    runs.add7(runStart, y, z, c[o + 3], y, z, runIdx);
                    if (runs.size / 7 > UNDO_MAX_RUNS) return null;
                }
            }
        }
        if (runs.size == 0) return null;
        return new Job("undo", runs.trimmed(), runs.size / 7, pal.toArray(new String[0]));
    }

    /** BlockState -> chaine utilisable dans /setblock et /fill, ex. minecraft:oak_stairs[facing=north,half=bottom]. */
    private static String ser(BlockState st) {
        StringBuilder sb = new StringBuilder(BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
        Collection<Property<?>> props = st.getProperties();
        if (!props.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Property<?> p : props) {
                if (!first) sb.append(',');
                first = false;
                sb.append(p.getName()).append('=').append(valueName(st, p));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static <T extends Comparable<T>> String valueName(BlockState st, Property<T> p) {
        return p.getName(st.getValue(p));
    }

    // ---------- hauteur du monde (sans dependre d'un nom d'API) ----------

    private static int minY(ClientLevel l) {
        int lo = -(1 << 16), hi = 0; // lo : hors du monde, hi : dans le monde
        while (hi - lo > 1) {
            int mid = (lo + hi) >> 1;
            if (l.isOutsideBuildHeight(new BlockPos(0, mid, 0))) lo = mid; else hi = mid;
        }
        return hi;
    }

    private static int maxY(ClientLevel l) {
        int lo = 0, hi = 1 << 16; // lo : dans le monde, hi : hors du monde
        while (hi - lo > 1) {
            int mid = (lo + hi) >> 1;
            if (l.isOutsideBuildHeight(new BlockPos(0, mid, 0))) hi = mid; else lo = mid;
        }
        return lo;
    }
}
