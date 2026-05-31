package com.parking.platform.storage.controller;

import com.parking.platform.common.dto.ApiResponse;
import com.parking.platform.storage.entity.StoredObject;
import com.parking.platform.storage.service.StorageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/storage")
public class StorageController {

    private final StorageService storageService;

    public StorageController(StorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping("/{bucket}/upload")
    public ApiResponse<StoredObject> upload(
            @PathVariable String bucket,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String key,
            @RequestHeader(required = false) Map<String, String> headers) throws IOException {

        String actualKey = key != null ? key : file.getOriginalFilename();
        Map<String, String> metadata = new HashMap<>();
        headers.forEach((k, v) -> {
            if (k.startsWith("x-amz-meta-")) {
                metadata.put(k.substring(11), v);
            }
        });

        StoredObject result = storageService.upload(bucket, actualKey, file.getBytes(), file.getContentType(), metadata);
        return ApiResponse.created(result);
    }

    @GetMapping("/{bucket}/{key}")
    public ResponseEntity<byte[]> download(@PathVariable String bucket, @PathVariable String key) {
        byte[] content = storageService.download(bucket, key);
        StoredObject obj = storageService.getObjectMetadata(bucket, key);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, obj != null && obj.getContentType() != null ? obj.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(content.length))
                .body(content);
    }

    @GetMapping("/{bucket}/{key}/metadata")
    public ApiResponse<StoredObject> getMetadata(@PathVariable String bucket, @PathVariable String key) {
        StoredObject obj = storageService.getObjectMetadata(bucket, key);
        return obj != null ? ApiResponse.success(obj) : ApiResponse.notFound("Object not found");
    }

    @DeleteMapping("/{bucket}/{key}")
    public ApiResponse<Void> delete(@PathVariable String bucket, @PathVariable String key) {
        boolean deleted = storageService.delete(bucket, key);
        return deleted ? ApiResponse.noContent() : ApiResponse.notFound("Object not found");
    }

    @GetMapping("/{bucket}")
    public ApiResponse<List<StoredObject>> list(
            @PathVariable String bucket,
            @RequestParam(required = false) String prefix,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "50") Integer size) {
        return ApiResponse.success(storageService.listObjects(bucket, prefix, page, size));
    }

    @PostMapping("/{bucket}/{key}/presign")
    public ApiResponse<String> presign(
            @PathVariable String bucket,
            @PathVariable String key,
            @RequestParam(defaultValue = "60") long expirationMinutes) {
        String url = storageService.generatePresignedUrl(bucket, key, expirationMinutes);
        return url != null ? ApiResponse.success(url) : ApiResponse.notFound("Object not found");
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Long>> getStats() {
        return ApiResponse.success(storageService.getStatistics());
    }
}
