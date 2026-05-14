package me.justindevb.replay.playback;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import me.justindevb.replay.chunk.ChunkCoordinate;
import me.justindevb.replay.storage.binary.BinaryPacketFriendlyChunkPayloadCodec;

import java.io.IOException;

interface ReplayChunkPacketPreparer {

    PacketFriendlyChunkColumnBuilder.PreparedChunkPacket prepare(
            ChunkCoordinate coordinate,
            BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload payload,
            ClientVersion clientVersion
    ) throws IOException;
}