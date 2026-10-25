package com.ibuild;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
 * Stick : /ibuild stick met un "stick fantome" dans ta barre (visible seulement chez toi, le serveur voit un
 * emplacement vide). Clic droit sur un bloc = place le build, sneak + clic = build suivant.
 */
public final class IBuildClient implements ClientModInitializer {
    /** Undo disponible seulement si le build est assez petit pour garder l'etat precedent du monde. */
    private static final long UNDO_MAX_CELLS = 1_500_000;
    private static final int UNDO_MAX_RUNS = 30_000;

    private static String selected;
    private static int perTick = 4;
    private static long fillLimit = 32_768; // = gamerule commandModificationBlockLimit (defaut vanilla)
    private static boolean planning;
    private static Job job;
    private static Job undoJob;
    /** Slot de la barre (0-8) ou le stick fantome est affiche, -1 si desactive. */
    private static int ghostSlot = -1;

    /** Liste de pavés : 7 int par pave (x0 y0 z0 x1 y1 z1 palette), coordonnees MONDE. */
    private static final class Job {
        final String name;
        final int[] pc;
        final int n;
        final String[] pal;
        int i = 0, ticks = 0;
        Job(String name, int[] pc, int n, String[] pal) { this.name = name; this.pc = pc; this.n = n; this.pal = pal; }
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

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("ibuild")
                        .requires(src -> clientMode())
                        .executes(c -> { help(); return 1; })
                        .then(ClientCommands.literal("help").executes(c -> { help(); return 1; }))
                        .then(ClientCommands.literal("list").executes(c -> { list(); return 1; }))
                        .then(ClientCommands.literal("reload").executes(c -> { msg(names().size() + " build(s) dans " + Builds.DIR); return 1; }))
                        .then(ClientCommands.literal("stick").executes(c -> { toggleGhost(); return 1; }))
                        .then(ClientCommands.literal("place").executes(c -> { placeAtCrosshair(); return 1; }))
                        .then(ClientCommands.literal("undo").executes(c -> { undo(); return 1; }))
                        .then(ClientCommands.literal("stop").executes(c -> { stop(); return 1; }))
                        .then(ClientCommands.literal("select").then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(names(), b))
                                .executes(c -> { select(StringArgumentType.getString(c, "name")); return 1; })))
                        .then(ClientCommands.literal("speed").then(ClientCommands.argument("n", IntegerArgumentType.integer(1, 50))
                                .executes(c -> {
                                    perTick = IntegerArgumentType.getInteger(c, "n");
                                    msg("Vitesse : " + perTick + " commande(s) par tick.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("limit").then(ClientCommands.argument("n", IntegerArgumentType.integer(1, 2_000_000))
                                .executes(c -> {
                                    fillLimit = IntegerArgumentType.getInteger(c, "n");
                                    msg("Taille max d'un /fill : " + fillLimit + " blocs.");
                                    return 1;
                                })))));
    }

    // ---------- detection du mod cote serveur ----------

    /** Vrai si ce serveur a le mod (solo / LAN, ou serveur qui a declare IBuildPresence). */
    private static boolean serverHasMod() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getSingleplayerServer() != null || ClientPlayNetworking.canSend(IBuildPresence.TYPE);
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
        if (mc.screen instanceof AbstractContainerScreen<?>) {
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
        msg("/ibuild list | reload | select <nom> | place | undo | stop | speed <1-50> | limit <taille>");
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
                        Boxes.split(base.getX() + Math.min(a[0], b[0]), wy0, base.getZ() + Math.min(a[1], b[1]),
                                base.getX() + Math.max(a[0], b[0]), wy1, base.getZ() + Math.max(a[1], b[1]),
                                limit, (px0, py0, pz0, px1, py1, pz1) -> {
                                    out.add7(px0, py0, pz0, px1, py1, pz1, pi);
                                    cells[0] += (long) (px1 - px0 + 1) * (py1 - py0 + 1) * (pz1 - pz0 + 1);
                                });
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
        job = new Job(name, p.pc(), p.n(), p.pal());
        double secs = p.n() / (perTick * 20.0);
        msg("Construction de \"" + name + "\" : " + p.n() + " commandes (~" + Math.round(secs) + " s).");
        if (undoJob == null) msg("Build trop gros pour /ibuild undo (seul /ibuild stop est possible).");
        if (p.extent() > 160) msg("Attention : build large (" + p.extent() + " blocs). /fill echoue sur les zones non chargees par le serveur.");
    }

    // ---------- envoi des commandes ----------

    private static void tick(Minecraft mc) {
        tickGhost(mc);
        Job j = job;
        if (j == null) return;
        if (mc.player == null || mc.getConnection() == null) { job = null; return; }
        try {
            int end = Math.min(j.n, j.i + perTick);
            for (int k = j.i; k < end; k++) mc.getConnection().sendCommand(command(j, k));
            j.i = end;
            if (end >= j.n) {
                job = null;
                msg("\"" + j.name + "\" termine. (/ibuild undo pour annuler)");
            } else if (++j.ticks % 10 == 0) {
                bar(j.name + " : " + (100L * end / j.n) + " %");
            }
        } catch (Throwable t) {
            job = null;
            msg("Build interrompu : " + t);
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
        msg("Build arrete (les blocs deja places restent ; /ibuild undo pour tout annuler).");
    }

    private static void undo() {
        if (planning) { msg("Preparation en cours, reessaie dans un instant."); return; }
        if (undoJob == null) { msg("Rien a annuler."); return; }
        job = undoJob;
        undoJob = null;
        msg("Annulation en cours (" + job.n + " commandes)...");
    }

    // ---------- undo : etat precedent du monde, lu cote client (chunks charges chez le joueur) ----------

    private static Job snapshot(ClientLevel level, Plan p) {
        if (p.cells() > UNDO_MAX_CELLS) return null;
        Map<BlockState, Integer> idx = new HashMap<>();
        List<String> pal = new ArrayList<>();
        IntBuf runs = new IntBuf();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int k = 0; k < p.n(); k++) {
            int o = k * 7;
            int[] c = p.pc();
            for (int y = c[o + 1]; y <= c[o + 4]; y++) {
                for 
