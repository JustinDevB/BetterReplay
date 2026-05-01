package me.justindevb.replay.playback;

import me.justindevb.replay.chunk.ChunkCoordinate;
import me.justindevb.replay.storage.binary.BinaryPacketFriendlyChunkPayloadCodec;
import org.bukkit.entity.Player;

import java.io.IOException;

interface ReplayChunkSnapshotSender {

    void send(
            Player viewer,
            ChunkCoordinate coordinate,
            BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload payload
    ) throws IOException;
}