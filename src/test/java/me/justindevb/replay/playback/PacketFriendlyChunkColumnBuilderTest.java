package me.justindevb.replay.playback;

import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import me.justindevb.replay.storage.binary.BinaryPacketFriendlyChunkPayloadCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketFriendlyChunkColumnBuilderTest {

    @Test
    void hasFluidState_detectsFluidAndWaterloggedStatesWithoutPacketEventsFluidApi() {
        assertTrue(PacketFriendlyChunkColumnBuilder.hasFluidState("minecraft:water[level=0]"));
        assertTrue(PacketFriendlyChunkColumnBuilder.hasFluidState("minecraft:lava[level=0]"));
        assertTrue(PacketFriendlyChunkColumnBuilder.hasFluidState("minecraft:bubble_column[drag=false]"));
        assertTrue(PacketFriendlyChunkColumnBuilder.hasFluidState("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=true]"));
        assertFalse(PacketFriendlyChunkColumnBuilder.hasFluidState("minecraft:stone"));
        assertFalse(PacketFriendlyChunkColumnBuilder.hasFluidState("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"));
    }

    @Test
    void buildLightData_returnsNonNullEmptyMasksForPacketFriendlyChunks() {
        PacketFriendlyChunkColumnBuilder builder = new PacketFriendlyChunkColumnBuilder();

        LightData lightData = builder.buildLightData(new BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload(
                0,
                List.of(new BinaryPacketFriendlyChunkPayloadCodec.SectionPayload(
                        List.of("minecraft:air"),
                        0,
                        new long[0],
                        List.of("minecraft:plains"),
                        0,
                        new long[0])),
                List.of()));

        assertNotNull(lightData.getSkyLightMask());
        assertNotNull(lightData.getBlockLightMask());
        assertNotNull(lightData.getEmptySkyLightMask());
        assertNotNull(lightData.getEmptyBlockLightMask());
        assertEquals(0, lightData.getSkyLightCount());
        assertEquals(0, lightData.getBlockLightCount());
        assertEquals(0, lightData.getSkyLightArray().length);
        assertEquals(0, lightData.getBlockLightArray().length);
    }

    @Test
    void resolveFluidPaletteStates_precomputesPerPaletteEntryAndHandlesEmptyPalettes() {
        assertArrayEquals(
                new boolean[]{true, false, true},
                PacketFriendlyChunkColumnBuilder.resolveFluidPaletteStates(
                        List.of("minecraft:water[level=0]", "minecraft:stone", "minecraft:oak_stairs[waterlogged=true]")));
        assertArrayEquals(
                new boolean[]{false},
                PacketFriendlyChunkColumnBuilder.resolveFluidPaletteStates(List.of()));
    }

    @Test
    void resolvePaletteIds_resolvesEachPaletteEntryOnceAndHandlesEmptyPalettes() {
        AtomicInteger calls = new AtomicInteger();

        assertArrayEquals(
            new int[]{6, 6, 4},
                PacketFriendlyChunkColumnBuilder.resolvePaletteIds(
                        List.of("plains", "plains", "void"),
                        key -> {
                            calls.incrementAndGet();
                            return key.length();
                        },
                        99));
        assertEquals(3, calls.get());
        assertArrayEquals(
                new int[]{99},
                PacketFriendlyChunkColumnBuilder.resolvePaletteIds(List.of(), key -> 1, 99));
    }
}