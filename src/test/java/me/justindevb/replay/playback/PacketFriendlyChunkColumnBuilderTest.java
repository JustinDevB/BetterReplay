package me.justindevb.replay.playback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}