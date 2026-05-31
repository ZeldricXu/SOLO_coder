package com.delivery.tracker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delivery.tracker.entity.StoredFile;
import com.delivery.tracker.mapper.StoredFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageManagementService {

    private final StoredFileMapper storedFileMapper;
    private static final String STORAGE_BASE_PATH = "/tmp/delivery-tracker/storage";

    public Mono<StoredFile> storeFile(String originalName, byte[] content, String contentType, String lifecyclePolicy) {
        return Mono.fromCallable(() -> {
            String fileId = "file_" + UUID.randomUUID().toString().replace("-", "");
            String storedPath = STORAGE_BASE_PATH + "/" + fileId + "_" + originalName;

            Path path = Paths.get(storedPath);
            Files.createDirectories(path.getParent());
            Files.write(path, content);

            StoredFile storedFile = new StoredFile();
            storedFile.setFileId(fileId);
            storedFile.setOriginalName(originalName);
            storedFile.setStoredPath(storedPath);
            storedFile.setFileSize((long) content.length);
            storedFile.setContentType(contentType);
            storedFile.setLifecyclePolicy(lifecyclePolicy);
            storedFile.setExpireAt(calculateExpireDate(lifecyclePolicy));
            storedFileMapper.insert(storedFile);

            log.info("文件存储成功: fileId={}, size={}", fileId, content.length);
            return storedFile;
        });
    }

    public Mono<byte[]> getFile(String fileId) {
        return Mono.fromCallable(() -> {
            StoredFile storedFile = storedFileMapper.selectOne(
                    new LambdaQueryWrapper<StoredFile>()
                            .eq(StoredFile::getFileId, fileId)
            );
            if (storedFile == null) {
                throw new RuntimeException("文件不存在: " + fileId);
            }

            Path path = Paths.get(storedFile.getStoredPath());
            return Files.readAllBytes(path);
        });
    }

    public Mono<Void> deleteFile(String fileId) {
        return Mono.fromCallable(() -> {
            StoredFile storedFile = storedFileMapper.selectOne(
                    new LambdaQueryWrapper<StoredFile>()
                            .eq(StoredFile::getFileId, fileId)
            );
            if (storedFile != null) {
                Path path = Paths.get(storedFile.getStoredPath());
                Files.deleteIfExists(path);
                storedFileMapper.deleteById(storedFile.getId());
                log.info("文件删除成功: fileId={}", fileId);
            }
            return null;
        });
    }

    public Flux<StoredFile> cleanupExpiredFiles() {
        return Mono.fromCallable(() ->
                storedFileMapper.selectList(
                        new LambdaQueryWrapper<StoredFile>()
                                .isNotNull(StoredFile::getExpireAt)
                                .le(StoredFile::getExpireAt, LocalDateTime.now())
                )
        )
                .flatMapMany(Flux::fromIterable)
                .flatMap(file -> deleteFile(file.getFileId()).thenReturn(file));
    }

    private LocalDateTime calculateExpireDate(String lifecyclePolicy) {
        if (lifecyclePolicy == null) {
            return null;
        }
        return switch (lifecyclePolicy) {
            case "ONE_DAY" -> LocalDateTime.now().plusDays(1);
            case "ONE_WEEK" -> LocalDateTime.now().plusWeeks(1);
            case "ONE_MONTH" -> LocalDateTime.now().plusMonths(1);
            case "THREE_MONTHS" -> LocalDateTime.now().plusMonths(3);
            case "ONE_YEAR" -> LocalDateTime.now().plusYears(1);
            default -> null;
        };
    }

    public Mono<StoredFile> getFileInfo(String fileId) {
        return Mono.fromCallable(() ->
                storedFileMapper.selectOne(
                        new LambdaQueryWrapper<StoredFile>()
                                .eq(StoredFile::getFileId, fileId)
                )
        );
    }
}
