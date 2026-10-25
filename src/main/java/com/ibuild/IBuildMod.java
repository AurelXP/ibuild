package com.ibuild;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

public class IBuildMod implements ModInitializer {
    public static final String MOD_ID = "ibuild";
    public static Item BUILDER_STICK;

    @Override
    public void onInitialize() {
        // Permet au client de savoir que ce serveur a le mod (voir IBuildPresence).
        PayloadTypeRegistry.serverboundPlay().register(IBuildPresence.TYPE, IBuildPresence.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(IBuildPresence.TYPE, (payload, context) -> {});

        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "builder_stick"));
        BUILDER_STICK = Registry.register(BuiltInRegistries.ITEM, key,
                new BuilderStickItem(new Item.Properties()
                        .setId(key)
                        .stacksTo(1)
                        .rarity(Rarity.EPIC)
                        .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)));

        Builds.init();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("ibuild")
                        .then(Commands.literal("list").executes(c -> {
                            Builds.list(c.getSource().getPlayerOrException());
                            return 1;
                        }))
                        .then(Commands.literal("reload").executes(c -> {
                            Builds.reloadMsg(c.getSource().getPlayerOrException());
                            return 1;
                        }))
                        .then(Commands.literal("undo").executes(c -> {
                            Builds.undo(c.getSource().getPlayerOrException());
                            return 1;
                        }))
                        .then(Commands.literal("stick").executes(c -> {
                            c.getSource().getPlayerOrException().getInventory().add(new ItemStack(BUILDER_STICK));
                            return 1;
                        }))
                        .then(Commands.literal("select").then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(Builds.names(), b))
                                .executes(c -> {
                                    Builds.select(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name"));
                                    return 1;
                                })))));
    }

    static Component txt(String s) { return Component.literal(s); }
}
