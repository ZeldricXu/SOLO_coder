package com.delivery.tracker.controller;

import com.delivery.tracker.common.Result;
import com.delivery.tracker.entity.StoredFile;
import com.delivery.tracker.service.StorageManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageManagementService storageService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Result<StoredFile>> uploadFile(
            @RequestPart("file") FilePart file,
            @RequestParam(required = false) String lifecyclePolicy) {

        return file.content()
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    return bytes;
                })
                .collectList()
                .map(list -> {
                    byte[] combined = new byte[list.stream().mapToInt(b -> b.length).sum()];
                    int offset = 0;
                    for (byte[] bytes : list) {
                        System.arraycopy(bytes, 0, combined, offset, bytes.length);
                        offset += bytes.length;
                    }
                    return combined;
                })
                .flatMap(bytes -> storageService.storeFile(
                        file.filename(),
                        bytes,
                        file.headers().getContentType().toString(),
                        lifecyclePolicy
                ))
                .map(Result::success);
    }

    @GetMapping("/{fileId}")
    public Mono<ResponseEntity<byte[]>> downloadFile(@PathVariable String fileId) {
        return storageService.getFile(fileId)
                .flatMap(bytes -> storageService.getFileInfo(fileId)
                        .map(fileInfo -> ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileInfo.getOriginalName() + "\"")
                                .contentType(MediaType.parseMediaType(fileInfo.getContentType()))
                                .body(bytes))
                );
    }

    @GetMapping("/{fileId}/info")
    public Mono<Result<StoredFile>> getFileInfo(@PathVariable String fileId) {
        return storageService.getFileInfo(fileId)
                .map(Result::success);
    }

    @DeleteMapping("/{fileId}")
    public Mono<Result<Void>> deleteFile(@PathVariable String fileId) {
        return storageService.deleteFile(fileId)
                .then(Mono.just(Result.success()));
    }

    @PostMapping("/cleanup")
    public Mono<Result<Map<String, Object>>> cleanupExpired() {
        return storageService.cleanupExpiredFiles()
                .collectList()
                .map(files -> Result.success(Map.of(
                        "cleanedCount", files.size(),
                        "files", files.stream().map(StoredFile::getFileId).toList()
                )));
    }
}
