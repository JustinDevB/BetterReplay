package me.justindevb.replay.chunk;

import org.junit.jupiter.api.Test;

import java.io.DataOutput;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class BlockEntityNbtReflectionSupportTest {

    @Test
    void extractNamedCompoundBytes_serializesSnapshotNbtWhenReflectionFindsWriter() {
        byte[] bytes = BlockEntityNbtReflectionSupport.extractNamedCompoundBytes(new SnapshotHolder());

        assertArrayEquals(new byte[] {
                0x0A, 0x00, 0x00,
                0x01, 0x00, 0x01, 'x', 0x05,
                0x00
        }, bytes);
    }

    @Test
    void extractNamedCompoundBytes_fallsBackToEmptyCompoundWhenNoSnapshotMethodExists() {
        assertArrayEquals(
                new byte[] {0x0A, 0x00, 0x00, 0x00},
                BlockEntityNbtReflectionSupport.extractNamedCompoundBytes(new Object()));
    }

    private static final class SnapshotHolder {
        public FakeCompound getSnapshotNBT() {
            return new FakeCompound();
        }
    }

    private static final class FakeCompound {
        public void write(DataOutput out) throws IOException {
            out.writeByte(0x01);
            out.writeShort(1);
            out.writeByte('x');
            out.writeByte(0x05);
            out.writeByte(0x00);
        }
    }
}