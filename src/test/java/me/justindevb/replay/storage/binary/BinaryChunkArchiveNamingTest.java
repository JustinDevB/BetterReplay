package me.justindevb.replay.storage.binary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinaryChunkArchiveNamingTest {

    @Test
    void percentEncodesUnsafeWorldNameBytesInArchivePaths() {
        assertEquals(
                "chunks/world%20name%2Fnether/r.-2.7.brregion",
                BinaryChunkArchiveNaming.regionEntryName("world name/nether", -2, 7));
    }

    @Test
    void leavesSafeWorldNameBytesUntouched() {
        assertEquals("world_the-end.1", BinaryChunkArchiveNaming.worldDirectory("world_the-end.1"));
    }

    @Test
    void rejectsBlankWorldNames() {
        assertThrows(IllegalArgumentException.class, () -> BinaryChunkArchiveNaming.regionEntryName(" ", 0, 0));
    }
}