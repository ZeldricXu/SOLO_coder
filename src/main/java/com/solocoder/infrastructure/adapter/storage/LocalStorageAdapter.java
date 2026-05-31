package com.solocoder.infrastructure.adapter.storage;

import com.solocoder.domain.model.CoreEntity;
import com.solocoder.domain.port.StoragePort;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

public class LocalStorageAdapter implements StoragePort {

    private String basePath;

    private Path storageRoot;

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public void init() throws IOException {
        storageRoot = Paths.get(basePath);
        if (!Files.exists(storageRoot)) {
            Files.createDirectories(storageRoot);
        }
    }

    @Override
    public Mono<String> storeFile(String fileName, InputStream content, long size, Map<String, String> metadata) {
        return Mono.fromCallable(() -> {
            String fileId = "file_" + UUID.randomUUID().toString().replace("-", "");
            Path targetPath = storageRoot.resolve(fileId + "_" + fileName);

            try (FileOutputStream fos = new FileOutputStream(targetPath.toFile())) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = content.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }

            saveMetadata(fileId, fileName, size, metadata, targetPath);
            return fileId;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<InputStream> retrieveFile(String fileId) {
        return Mono.fromCallable(() -> {
            Path filePath = findFilePath(fileId);
            if (filePath == null) {
                return null;
            }
            return new FileInputStream(filePath.toFile());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> deleteFile(String fileId) {
        return Mono.fromCallable(() -> {
            Path filePath = findFilePath(fileId);
            if (filePath != null) {
                Files.deleteIfExists(filePath);
            }
            Path metadataPath = storageRoot.resolve(fileId + ".meta");
            return Files.deleteIfExists(metadataPath);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<CoreEntity> getFileMetadata(String fileId) {
        return Mono.fromCallable(() -> loadMetadata(fileId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<CoreEntity> listFiles(String prefix, Integer page, Integer size) {
        return Flux.using(() -> Files.list(storageRoot),
                        stream -> {
                            Stream<Path> filtered = stream
                                    .filter(path -> path.toString().endsWith(".meta"))
                                    .skip((long) (page - 1) * size)
                                    .limit(size);
                            return Flux.fromStream(filtered)
                                    .map(path -> {
                                        String fileId = path.getFileName().toString().replace(".meta", "");
                                        return loadMetadata(fileId);
                                    });
                        },
                        Stream::close)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> applyLifecyclePolicy(String fileId, String policyName) {
        return Mono.fromRunnable(() -> {
            CoreEntity metadata = loadMetadata(fileId);
            if (metadata != null) {
                Map<String, Object> attributes = new HashMap<>(metadata.getAttributes());
                attributes.put("lifecyclePolicy", policyName);
                attributes.put("policyAppliedAt", Instant.now().toString());
                metadata.setAttributes(attributes);
                metadata.setUpdatedAt(Instant.now());
                saveMetadataEntity(metadata);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Flux<CoreEntity> findExpiredFiles(Instant expirationTime) {
        return Flux.using(() -> Files.list(storageRoot),
                        stream -> Flux.fromStream(stream
                                        .filter(path -> path.toString().endsWith(".meta"))
                                        .map(path -> {
                                            String fileId = path.getFileName().toString().replace(".meta", "");
                                            return loadMetadata(fileId);
                                        })
                                        .filter(meta -> meta != null && meta.getCreatedAt().isBefore(expirationTime))),
                        Stream::close)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> archiveFile(String fileId, String targetStorageClass) {
        return Mono.fromRunnable(() -> {
            CoreEntity metadata = loadMetadata(fileId);
            if (metadata != null) {
                Map<String, Object> attributes = new HashMap<>(metadata.getAttributes());
                attributes.put("storageClass", targetStorageClass);
                attributes.put("archivedAt", Instant.now().toString());
                metadata.setAttributes(attributes);
                metadata.setStatus("archived");
                metadata.setUpdatedAt(Instant.now());
                saveMetadataEntity(metadata);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void saveMetadata(String fileId, String fileName, long size,
                              Map<String, String> metadata, Path filePath) {
        CoreEntity entity = CoreEntity.builder()
                .id(fileId)
                .type("file")
                .status("active")
                .attributes(new HashMap<>(Map.of(
                        "fileName", fileName,
                        "fileSize", size,
                        "filePath", filePath.toString(),
                        "storageClass", "standard"
                )))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        if (metadata != null) {
            entity.getAttributes().putAll(new HashMap<>(metadata));
        }

        saveMetadataEntity(entity);
    }

    private void saveMetadataEntity(CoreEntity entity) {
        try {
            Path metaPath = storageRoot.resolve(entity.getId() + ".meta");
            StringBuilder sb = new StringBuilder();
            sb.append(entity.getId()).append("\n");
            sb.append(entity.getType()).append("\n");
            sb.append(entity.getStatus()).append("\n");
            sb.append(entity.getCreatedAt()).append("\n");
            sb.append(entity.getUpdatedAt()).append("\n");
            sb.append(entity.getAttributes()).append("\n");
            Files.writeString(metaPath, sb.toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to save metadata", e);
        }
    }

    private CoreEntity loadMetadata(String fileId) {
        try {
            Path metaPath = storageRoot.resolve(fileId + ".meta");
            if (!Files.exists(metaPath)) {
                return null;
            }
            String[] lines = Files.readString(metaPath).split("\n");
            return CoreEntity.builder()
                    .id(lines[0])
                    .type(lines[1])
                    .status(lines[2])
                    .createdAt(Instant.parse(lines[3]))
                    .updatedAt(Instant.parse(lines[4]))
                    .attributes(parseAttributes(lines[5]))
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> parseAttributes(String line) {
        Map<String, Object> attributes = new HashMap<>();
        if (line.startsWith("{") && line.endsWith("}")) {
            String content = line.substring(1, line.length() - 1);
            for (String pair : content.split(", ")) {
                String[] keyValue = pair.split("=");
                if (keyValue.length == 2) {
                    attributes.put(keyValue[0].trim(), keyValue[1].trim());
                }
            }
        }
        return attributes;
    }

    private Path findFilePath(String fileId) {
        CoreEntity metadata = loadMetadata(fileId);
        if (metadata != null && metadata.getAttributes().containsKey("filePath")) {
            return Paths.get(metadata.getAttributes().get("filePath").toString());
        }
        return null;
    }
}
