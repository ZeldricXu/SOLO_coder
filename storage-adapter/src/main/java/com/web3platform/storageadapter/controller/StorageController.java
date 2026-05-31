package com.web3platform.storageadapter.controller;

import com.web3platform.storageadapter.model.*;
import com.web3platform.storageadapter.service.BatchUploadService;
import com.web3platform.storageadapter.service.ChunkedStreamService;
import com.web3platform.storageadapter.service.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;
    private final ChunkedStreamService chunkedStreamService;
    private final BatchUploadService batchUploadService;

    @PostMapping("/upload")
    public ResponseEntity<StorageUploadResponse> upload(@RequestBody StorageUploadRequest request) {
        StorageUploadResponse response = storageService.upload(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/download/{cid}")
    public ResponseEntity<byte[]> download(@PathVariable String cid) {
        byte[] data = storageService.download(cid);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + cid + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }

    @PostMapping("/pin/{cid}")
    public ResponseEntity<PinStatus> pin(@PathVariable String cid,
                                         @RequestParam String storageType) {
        PinStatus status = storageService.pin(cid, storageType);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/unpin/{cid}")
    public ResponseEntity<PinStatus> unpin(@PathVariable String cid,
                                           @RequestParam String storageType) {
        PinStatus status = storageService.unpin(cid, storageType);
        return ResponseEntity.ok(status);
    }

    @GetMapping("/status/{cid}")
    public ResponseEntity<ContentInfo> getStatus(@PathVariable String cid,
                                                  @RequestParam String storageType) {
        ContentInfo info = storageService.getStatus(cid, storageType);
        return ResponseEntity.ok(info);
    }

    @PostMapping("/chunked/init")
    public ResponseEntity<ChunkedUploadStatus> initChunkedUpload(
            @RequestParam String fileName,
            @RequestParam String storageType,
            @RequestParam int totalChunks,
            @RequestParam(defaultValue = "true") boolean pin) {
        ChunkedUploadStatus status = chunkedStreamService.initChunkedUpload(fileName, storageType, totalChunks, pin);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/chunked/upload")
    public ResponseEntity<ChunkedUploadStatus> uploadChunk(@RequestBody ChunkedUploadRequest request) {
        ChunkedUploadStatus status = chunkedStreamService.uploadChunk(request);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/chunked/complete/{uploadId}")
    public ResponseEntity<ChunkedUploadStatus> completeChunkedUpload(@PathVariable String uploadId) {
        ChunkedUploadStatus status = chunkedStreamService.completeChunkedUpload(uploadId);
        return ResponseEntity.ok(status);
    }

    @GetMapping("/chunked/status/{uploadId}")
    public ResponseEntity<ChunkedUploadStatus> getChunkedUploadStatus(@PathVariable String uploadId) {
        ChunkedUploadStatus status = chunkedStreamService.getChunkedUploadStatus(uploadId);
        return ResponseEntity.ok(status);
    }

    @DeleteMapping("/chunked/abort/{uploadId}")
    public ResponseEntity<Void> abortChunkedUpload(@PathVariable String uploadId) {
        chunkedStreamService.abortChunkedUpload(uploadId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch")
    public ResponseEntity<BatchUploadResult> batchUpload(@RequestBody BatchUploadRequest request) {
        BatchUploadResult result = batchUploadService.batchUpload(request);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/stream/{cid}")
    public ResponseEntity<StreamingResponseBody> streamDownload(
            @PathVariable String cid,
            @RequestParam(defaultValue = "IPFS") String storageType) {
        StreamingResponseBody responseBody = outputStream -> {
            chunkedStreamService.streamDownload(cid, storageType, outputStream);
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + cid + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(responseBody);
    }
}
