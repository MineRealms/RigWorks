//? if neoforge {
/*
package cn.minerealms.rigworks.network;

import cn.minerealms.rigworks.character.core.CharacterAppearance;
import cn.minerealms.rigworks.character.core.CharacterPackageTransfer;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CharacterNetworkPayloadTest {
    @BeforeAll
    static void register() {
        CharacterAppearanceNetwork.register();
    }

    @Test
    void preservesAllTenPacketIdentitiesAndDirections() throws Exception {
        var bindings = bindings();
        assertEquals(10, bindings.size());
        Set<String> serverbound = bindings.values().stream().filter(binding -> (boolean) field(binding, "serverbound"))
                .map(binding -> ((CustomPacketPayload.Type<?>) field(binding, "type")).id().toString()).collect(Collectors.toSet());
        assertEquals(Set.of("rigworks:appearance_0", "rigworks:appearance_4", "rigworks:appearance_5",
                "rigworks:appearance_6", "rigworks:appearance_10"), serverbound);
        assertEquals(10, bindings.values().stream().map(binding -> field(binding, "type")).distinct().count());
    }

    @Test
    void optionalPacketsRequireTheCompleteProtocolBeforeEnablingSharing() throws Exception {
        Set<CustomPacketPayload.Type<?>> negotiated = bindings().values().stream()
                .map(binding -> (CustomPacketPayload.Type<?>) field(binding, "type")).collect(Collectors.toSet());
        assertTrue(CharacterNetworkChannel.completeProtocol(negotiated::contains));
        assertFalse(CharacterNetworkChannel.completeProtocol(type -> false));
        for (var missing : negotiated) {
            assertFalse(CharacterNetworkChannel.completeProtocol(type -> negotiated.contains(type) && !type.equals(missing)),
                    "Missing optional payload must disable sharing: " + missing.id());
        }
    }

    @Test
    void serverRegistrationDoesNotResolveClientImplementationClasses() throws Exception {
        var loader = new ClassLoader(CharacterNetworkChannel.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.minecraft.client.") || name.startsWith("cn.minerealms.rigworks.character.client."))
                    throw new ClassNotFoundException("Client class unavailable in dedicated-server registration: " + name);
                if (!name.startsWith("cn.minerealms.rigworks.network.")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    var loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try (var input = getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (input == null) throw new ClassNotFoundException(name);
                            byte[] bytes = input.readAllBytes();
                            loaded = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException error) {
                            throw new ClassNotFoundException(name, error);
                        }
                    }
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        };
        var appearance = Class.forName(CharacterAppearanceNetwork.class.getName(), true, loader);
        appearance.getDeclaredMethod("register").invoke(null);
        for (String name : new String[]{"CharacterNetworkChannel", "CharacterNetworkChannel$Registration",
                "CharacterPackageNetwork", "CharacterVisualSavedData"})
            assertTrue(Class.forName("cn.minerealms.rigworks.network." + name, true, loader).getDeclaredMethods().length > 0);
    }

    @Test
    void catalogAndUploadHeadersKeepRevisionHashAndServerIdentity() throws Exception {
        var appearance = new CharacterAppearance(true, "test:profile.json", "SCRIPTED", false, 0);
        Object update = packet("CharacterAppearanceNetwork", "Update", UUID.randomUUID(), appearance,
                13L, 27L, "a".repeat(64), "b".repeat(64), UUID.randomUUID());
        assertEquals(update, roundTrip(update));
        Object start = packet("CharacterPackageNetwork", "Start", appearance, 13L, 27L,
                "b".repeat(64), "c".repeat(64), 39000);
        assertEquals(start, roundTrip(start));
        Object download = packet("CharacterPackageNetwork", "DownloadStart", "b".repeat(64), "c".repeat(64), 39000);
        assertEquals(download, roundTrip(download));
    }

    @Test
    void defaultMotionSelectionSurvivesAppearanceAndUploadPackets() throws Exception {
        var appearance = new CharacterAppearance(true, "test:profile.json", "DEFAULT", false, 7);
        Object update = packet("CharacterAppearanceNetwork", "Update", UUID.randomUUID(), appearance,
                13L, 27L, "a".repeat(64), "b".repeat(64), UUID.randomUUID());
        assertEquals(update, roundTrip(update));
        var uploadSelection = new CharacterAppearance(true, "test:profile.json", "DEFAULT", false, 0);
        Object start = packet("CharacterPackageNetwork", "Start", uploadSelection, 13L, 27L,
                "b".repeat(64), "c".repeat(64), 39000);
        assertEquals(start, roundTrip(start));
    }

    @Test
    void chunkDecodersPreserveTheByteBudgetInBothDirections() throws Exception {
        byte[] bytes = new byte[CharacterPackageTransfer.CHUNK_BYTES];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        for (String name : new String[]{"UploadPart", "DownloadPart"}) {
            Object packet = packet("CharacterPackageNetwork", name, "d".repeat(64), 3, bytes);
            Object decoded = roundTrip(packet);
            assertEquals("d".repeat(64), field(decoded, "hash"));
            assertEquals(3, (int) field(decoded, "index"));
            assertArrayEquals(bytes, (byte[]) field(decoded, "bytes"));
            FriendlyByteBuf oversized = new FriendlyByteBuf(Unpooled.buffer());
            try {
                oversized.writeUtf("d".repeat(64), 64);
                oversized.writeVarInt(3);
                oversized.writeByteArray(new byte[CharacterPackageTransfer.CHUNK_BYTES + 1]);
                assertThrows(DecoderException.class, () -> codec(packet).decode(oversized));
            } finally {
                oversized.release();
            }
        }
    }

    private static Object roundTrip(Object packet) throws Exception {
        var factory = CharacterNetworkChannel.class.getDeclaredMethod("payload", Object.class);
        factory.setAccessible(true);
        CustomPacketPayload encoded = (CustomPacketPayload) factory.invoke(null, packet);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec(packet).encode(buffer, encoded);
            Object decoded = codec(packet).decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return field(decoded, "value");
        } finally {
            buffer.release();
        }
    }

    private static Object packet(String owner, String name, Object... values) throws Exception {
        var type = Class.forName("cn.minerealms.rigworks.network." + owner + "$" + name);
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<?>, ?> bindings() throws Exception {
        var bindings = CharacterNetworkChannel.class.getDeclaredField("BINDINGS");
        bindings.setAccessible(true);
        return (Map<Class<?>, ?>) bindings.get(null);
    }

    @SuppressWarnings("unchecked")
    private static StreamCodec<FriendlyByteBuf, CustomPacketPayload> codec(Object packet) throws Exception {
        return (StreamCodec<FriendlyByteBuf, CustomPacketPayload>) field(bindings().get(packet.getClass()), "codec");
    }

    private static Object field(Object record, String name) {
        try {
            var accessor = record.getClass().getDeclaredMethod(name);
            accessor.setAccessible(true);
            return accessor.invoke(record);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
*///?}
