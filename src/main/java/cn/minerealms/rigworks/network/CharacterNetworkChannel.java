package cn.minerealms.rigworks.network;

import cn.minerealms.assimploader.util.ResourceLocations;
import cn.minerealms.rigworks.RigWorks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
//? if forge {
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
//?}
//? if neoforge {
/*
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.LinkedHashMap;
import java.util.Map;
*///?}

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** Keeps the bounded character protocol identical across the two loader transports. */
public final class CharacterNetworkChannel {
    private static final String VERSION = "2";
    //? if forge {
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocations.of(RigWorks.MODID, "appearance"), () -> VERSION,
            CharacterNetworkChannel::compatible, CharacterNetworkChannel::compatible);

    private static boolean compatible(String version) {
        return VERSION.equals(version) || NetworkRegistry.ABSENT.equals(version)
                || NetworkRegistry.ACCEPTVANILLA.equals(version);
    }
    //?}
    //? if neoforge {
    /*
    private static final Map<Class<?>, Binding<?>> BINDINGS = new LinkedHashMap<>();

    private record Payload<T>(CustomPacketPayload.Type<Payload<T>> type, T value) implements CustomPacketPayload {}
    private record Binding<T>(CustomPacketPayload.Type<Payload<T>> type,
                              StreamCodec<FriendlyByteBuf, Payload<T>> codec,
                              BiConsumer<T, ServerPlayer> handler, boolean serverbound) {}

    @EventBusSubscriber(modid = RigWorks.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {}

        @SubscribeEvent
        public static void register(RegisterPayloadHandlersEvent event) {
            CharacterAppearanceNetwork.register();
            PayloadRegistrar registrar = event.registrar(VERSION).optional();
            BINDINGS.values().forEach(binding -> register(registrar, binding));
        }

        private static <T> void register(PayloadRegistrar registrar, Binding<T> binding) {
            if (binding.serverbound) registrar.playToServer(binding.type, binding.codec, (packet, context) -> {
                if (context.player() instanceof ServerPlayer sender) binding.handler.accept(packet.value, sender);
            });
            else registrar.playToClient(binding.type, binding.codec,
                    (packet, context) -> binding.handler.accept(packet.value, null));
        }
    }

    private static <T> void register(Class<T> type, int id, BiConsumer<T, FriendlyByteBuf> encoder,
                                     Function<FriendlyByteBuf, T> decoder,
                                     BiConsumer<T, ServerPlayer> handler, boolean serverbound) {
        var payloadType = new CustomPacketPayload.Type<Payload<T>>(
                ResourceLocations.of(RigWorks.MODID, "appearance_" + id));
        StreamCodec<FriendlyByteBuf, Payload<T>> codec = CustomPacketPayload.codec(
                (packet, buffer) -> encoder.accept(packet.value, buffer),
                buffer -> new Payload<>(payloadType, decoder.apply(buffer)));
        if (BINDINGS.putIfAbsent(type, new Binding<>(payloadType, codec, handler, serverbound)) != null)
            throw new IllegalStateException("Duplicate RigWorks packet " + type.getName());
    }

    static boolean completeProtocol(java.util.function.Predicate<CustomPacketPayload.Type<?>> negotiated) {
        // Optional payloads are negotiated independently; one advertised ID cannot enable the whole relay.
        return !BINDINGS.isEmpty() && BINDINGS.values().stream().allMatch(binding -> negotiated.test(binding.type));
    }
    *///?}

    private CharacterNetworkChannel() {}

    static <T> void serverbound(Class<T> type, int id, BiConsumer<T, FriendlyByteBuf> encoder,
                                Function<FriendlyByteBuf, T> decoder, BiConsumer<T, ServerPlayer> handler) {
        //? if forge {
        CHANNEL.messageBuilder(type, id, NetworkDirection.PLAY_TO_SERVER)
                .encoder(encoder).decoder(decoder)
                .consumerMainThread((packet, context) -> handler.accept(packet, context.get().getSender())).add();
        //?}
        //? if neoforge {
        /*
        register(type, id, encoder, decoder, handler, true);
        *///?}
    }

    static <T> void clientbound(Class<T> type, int id, BiConsumer<T, FriendlyByteBuf> encoder,
                                Function<FriendlyByteBuf, T> decoder, Consumer<T> handler) {
        //? if forge {
        CHANNEL.messageBuilder(type, id, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(encoder).decoder(decoder).consumerMainThread((packet, context) -> handler.accept(packet)).add();
        //?}
        //? if neoforge {
        /*
        register(type, id, encoder, decoder, (packet, sender) -> handler.accept(packet), false);
        *///?}
    }

    static boolean available() {
        var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
        //? if forge {
        return connection != null && CHANNEL.isRemotePresent(connection.getConnection());
        //?}
        //? if neoforge {
        /*
        return connection != null && completeProtocol(connection::hasChannel);
        *///?}
    }

    static boolean available(ServerPlayer player) {
        //? if forge {
        return CHANNEL.isRemotePresent(player.connection.connection);
        //?}
        //? if neoforge {
        /*
        return completeProtocol(player.connection::hasChannel);
        *///?}
    }

    static <T> void sendToServer(T packet) {
        //? if forge {
        CHANNEL.sendToServer(packet);
        //?}
        //? if neoforge {
        /*
        PacketDistributor.sendToServer(payload(packet));
        *///?}
    }

    static <T> void send(ServerPlayer player, T packet) {
        //? if forge {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        //?}
        //? if neoforge {
        /*
        PacketDistributor.sendToPlayer(player, payload(packet));
        *///?}
    }

    //? if neoforge {
    /*
    @SuppressWarnings("unchecked")
    private static <T> Payload<T> payload(T packet) {
        Binding<T> binding = (Binding<T>) BINDINGS.get(packet.getClass());
        if (binding == null) throw new IllegalArgumentException("Unregistered RigWorks packet " + packet.getClass().getName());
        return new Payload<>(binding.type, packet);
    }
    *///?}
}
