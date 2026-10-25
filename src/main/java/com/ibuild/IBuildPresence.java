package com.ibuild;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Paquet vide, jamais envoye : il sert uniquement a declarer que le serveur a le mod.
 * Le client regarde (ClientPlayNetworking.canSend) si le serveur sait le recevoir : si oui, les vraies
 * commandes /ibuild du serveur sont utilisees ; sinon le mode client prend le relais.
 */
public record IBuildPresence() implements CustomPacketPayload {
    public static final IBuildPresence INSTANCE = new IBuildPresence();
    public static final CustomPacketPayload.Type<IBuildPresence> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("ibuild", "present"));
    public static final StreamCodec<FriendlyByteBuf, IBuildPresence> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
