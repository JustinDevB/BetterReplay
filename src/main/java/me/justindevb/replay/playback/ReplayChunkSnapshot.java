package me.justindevb.replay.playback;

import me.justindevb.replay.storage.binary.BinaryChunkPayloadCodec;
import me.justindevb.replay.storage.binary.BinaryPacketFriendlyChunkPayloadCodec;

public sealed interface ReplayChunkSnapshot permits ReplayChunkSnapshot.LegacyBlockStateSnapshot, ReplayChunkSnapshot.PacketFriendlySnapshot {

    record LegacyBlockStateSnapshot(BinaryChunkPayloadCodec.DecodedChunkPayload payload) implements ReplayChunkSnapshot {
    }

    record PacketFriendlySnapshot(BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload payload) implements ReplayChunkSnapshot {
    }
}