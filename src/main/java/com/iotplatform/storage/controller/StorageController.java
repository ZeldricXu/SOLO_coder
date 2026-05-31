package com.iotplatform.storage.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iotplatform.common.dto.PageQuery;
import com.iotplatform.common.dto.PageResult;
import com.iotplatform.common.dto.Result;
import com.iotplatform.storage.dto.ObjectDownloadDTO;
import com.iotplatform.storage.dto.ObjectUploadDTO;
import com.iotplatform.storage.entity.StorageObject;
import com.iotplatform.storage.service.StorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;

    @PostMapping("/upload")
    public Mono<Result<StorageObject>> uploadObject(@Valid @RequestBody ObjectUploadDTO dto) {
        return storageService.uploadObject(dto)
                .map(Result::success);
    }

    @PostMapping("/upload/file")
    public Mono<Result<StorageObject>> uploadFile(
            @RequestParam String bucketName,
            @RequestParam String objectKey,
            @RequestPart("file") FilePart file) {
        return file.content()
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    return bytes;
                })
                .collectList()
                .map(byteList -> {
                    byte[] allBytes = new byte[byteList.stream().mapToInt(b -> b.length).sum()];
                    int offset = 0;
                    for (byte[] bytes : byteList) {
                        System.arraycopy(bytes, 0, allBytes, offset, bytes.length);
                        offset += bytes.length;
                    }
                    return allBytes;
                })
                .flatMap(bytes -> {
                    ObjectUploadDTO dto = new ObjectUploadDTO();
                    dto.setBucketName(bucketName);
                    dto.setObjectKey(objectKey);
                    dto.setObjectName(file.filename());
                    dto.setContent(bytes);
                    return storageService.uploadObject(dto);
                })
                .map(Result::success);
    }

    @GetMapping("/{objectId}/download")
    public Mono<ResponseEntity<Resource>> downloadObject(@PathVariable String objectId) {
        return storageService.downloadObject(objectId)
                .map(dto -> {
                    ByteArrayResource resource = new ByteArrayResource(dto.getContent());
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + dto.getObjectName() + "\"")
                            .contentType(MediaType.parseMediaType(dto.getContentType()))
                            .contentLength(dto.getContentLength())
                            .body(resource);
                });
    }

    @GetMapping("/{objectId}/metadata")
    public Mono<Result<StorageObject>> getObjectMetadata(@PathVariable String objectId) {
        return storageService.getObjectMetadata(objectId)
                .map(Result::success);
    }

    @DeleteMapping("/{objectId}")
    public Mono<Result<Void>> deleteObject(@PathVariable String objectId) {
        return storageService.deleteObject(objectId)
                .then(Mono.just(Result.success(null)));
    }

    @GetMapping
    public Mono<Result<PageResult<StorageObject>>> listObjects(
            @RequestParam(required = false) String bucketName,
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) String provider,
            @ModelAttribute PageQuery pageQuery) {
        return storageService.listObjects(bucketName, prefix, provider,
                        pageQuery.getPageNum(), pageQuery.getPageSize())
                .map(page -> {
                    PageResult<StorageObject> pageResult = new PageResult<>(
                            page.getRecords(),
                            page.getTotal(),
                            page.getPages(),
                            page.getCurrent(),
                            page.getSize()
                    );
                    return Result.success(pageResult);
                });
    }

    @GetMapping("/buckets/{bucketName}/stats")
    public Mono<Result<Map<String, Object>>> getBucketStats(@PathVariable String bucketName) {
        return storageService.getBucketStats(bucketName)
                .map(Result::success);
    }

    @PostMapping("/{objectId}/presigned-url")
    public Mono<Result<String>> generatePresignedUrl(
            @PathVariable String objectId,
            @RequestParam(defaultValue = "3600") long expirySeconds) {
        return storageService.generatePresignedUrl(objectId, expirySeconds)
                .map(Result::success);
    }

    @PostMapping("/batch/delete")
    public Mono<Result<List<StorageObject>>> batchDelete(@RequestBody List<String> objectIds) {
        return storageService.batchDelete(objectIds)
                .map(Result::success);
    }

    @GetMapping("/exists")
    public Mono<Result<Boolean>> objectExists(
            @RequestParam String bucketName,
            @RequestParam String objectKey) {
        return storageService.objectExists(bucketName, objectKey)
                .map(Result::success);
    }

    @PostMapping("/{sourceObjectId}/copy")
    public Mono<Result<Void>> copyObject(
            @PathVariable String sourceObjectId,
            @RequestParam String destBucket,
            @RequestParam String destKey) {
        return storageService.copyObject(sourceObjectId, destBucket, destKey)
                .then(Mono.just(Result.success(null)));
    }
}
