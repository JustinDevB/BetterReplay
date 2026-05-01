package me.justindevb.replay.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Optional;

final class FileReplayProtectionStore {

    private final File metadataFolder;
    private final Gson gson;

    FileReplayProtectionStore(File dataFolder) {
        this(dataFolder, new GsonBuilder().setPrettyPrinting().create());
    }

    FileReplayProtectionStore(File dataFolder, Gson gson) {
        this.metadataFolder = new File(dataFolder, "replays-meta");
        this.gson = gson;
        if (!metadataFolder.exists()) {
            metadataFolder.mkdirs();
        }
    }

    Optional<ReplayProtectionMetadata> readProtection(String name) throws IOException {
        File metadataFile = resolveMetadataFile(name);
        if (!metadataFile.isFile()) {
            return Optional.empty();
        }

        StoredReplayProtectionMetadata stored = gson.fromJson(Files.readString(metadataFile.toPath(), StandardCharsets.UTF_8),
                StoredReplayProtectionMetadata.class);
        if (stored == null) {
            throw new IOException("Protection metadata file is empty: " + metadataFile.getName());
        }

        Instant protectedAt = stored.protectedAt() == null || stored.protectedAt().isBlank()
                ? null
                : Instant.parse(stored.protectedAt());
        String protectedBy = stored.protectedBy();

        if (stored.protectedFromDeletion()) {
            if (protectedAt == null) {
                throw new IOException("Protected replay metadata is missing protectedAt: " + metadataFile.getName());
            }
            if (protectedBy == null || protectedBy.isBlank()) {
                throw new IOException("Protected replay metadata is missing protectedBy: " + metadataFile.getName());
            }
        }

        return Optional.of(new ReplayProtectionMetadata(stored.protectedFromDeletion(), protectedAt, protectedBy));
    }

    ReplayProtectionResult protectReplay(String name, Instant protectedAt, String protectedBy) throws IOException {
        Optional<ReplayProtectionMetadata> existing = readProtection(name);
        if (existing.isPresent() && existing.get().protectedFromDeletion()) {
            return ReplayProtectionResult.ALREADY_PROTECTED;
        }

        write(name, new ReplayProtectionMetadata(true, protectedAt, protectedBy));
        return ReplayProtectionResult.UPDATED;
    }

    ReplayProtectionResult unprotectReplay(String name) throws IOException {
        Optional<ReplayProtectionMetadata> existing = readProtection(name);
        if (existing.isEmpty()) {
            return ReplayProtectionResult.ALREADY_UNPROTECTED;
        }
        ReplayProtectionMetadata current = existing.get();
        if (!current.protectedFromDeletion()) {
            return ReplayProtectionResult.ALREADY_UNPROTECTED;
        }

        write(name, new ReplayProtectionMetadata(false, current.protectedAt(), current.protectedBy()));
        return ReplayProtectionResult.UPDATED;
    }

    void deleteMetadata(String name) throws IOException {
        File metadataFile = resolveMetadataFile(name);
        if (metadataFile.exists() && !metadataFile.delete()) {
            throw new IOException("Failed to delete protection metadata for replay: " + name);
        }
    }

    File resolveMetadataFile(String name) {
        return new File(metadataFolder, name + ".json");
    }

    private void write(String name, ReplayProtectionMetadata metadata) throws IOException {
        File metadataFile = resolveMetadataFile(name);
        StoredReplayProtectionMetadata stored = new StoredReplayProtectionMetadata(
                metadata.protectedFromDeletion(),
                metadata.protectedAt() != null ? metadata.protectedAt().toString() : null,
                metadata.protectedBy());
        Files.writeString(metadataFile.toPath(), gson.toJson(stored), StandardCharsets.UTF_8);
    }

    record ReplayProtectionMetadata(boolean protectedFromDeletion, Instant protectedAt, String protectedBy) {
    }

    private record StoredReplayProtectionMetadata(boolean protectedFromDeletion, String protectedAt, String protectedBy) {
    }
}