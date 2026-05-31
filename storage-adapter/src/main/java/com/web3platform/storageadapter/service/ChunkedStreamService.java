package com.web3platform.storageadapter.service;

import com.web3platform.storageadapter.config.StorageConfig;
import com.web3platform.storageadapter.constant.StorageConstants;
import com.web3platform.storageadapter.exception.StorageErrorCode;
import com.web3platform.storageadapter.exception.StorageException;
import com.web3platform.storageadapter.model.ChunkedUploadRequest;
import com.web3platform.storageadapter.model.ChunkedUploadStatus;
import com.web3platform.storageadapter.model.StorageUploadRequest;
import com.web3platform.storageadapter.model.StorageUploadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkedStreamService {

    private final StorageProviderFactory providerFactory;
    private final StorageService storageService;
    private final StorageConfig storageConfig;

    private final Map<String, ChunkedUploadStatus> sessions = new ConcurrentHashMap<>();
    // 分段锁：每个uploadId对应一个锁对象，保护对应session的状态修改
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    @NonNull
    public ChunkedUploadStatus initChunkedUpload(@NonNull String fileName, @NonNull String storageType,
                                                 int totalChunks, boolean pin) {
        String uploadId = UUID.randomUUID().toString().replace("-", "");
        ChunkedUploadStatus status = ChunkedUploadStatus.builder()
                .uploadId(uploadId)
                .totalChunks(totalChunks)
                .uploadedChunks(0)
                .completed(false)
                .cids(new ArrayList<>(totalChunks))
                .storageType(storageType)
                .build();
        sessions.put(uploadId, status);
        sessionLocks.put(uploadId, new Object());
        log.info("Initialized chunked upload session: uploadId={}, totalChunks={}, storageType={}",
                uploadId, totalChunks, storageType);
        return status;
    }

    @NonNull
    public ChunkedUploadStatus uploadChunk(@NonNull ChunkedUploadRequest request) {
        ChunkedUploadStatus status = sessions.get(request.getUploadId());
        if (status == null) {
            throw new StorageException(StorageErrorCode.SESSION_NOT_FOUND,
                    "Upload session not found: " + request.getUploadId());
        }

        Object lock = sessionLocks.get(request.getUploadId());
        if (lock == null) {
            throw new StorageException(StorageErrorCode.SESSION_NOT_FOUND,
                    "Upload session already completed or aborted: " + request.getUploadId());
        }

        synchronized (lock) {
            validateSessionStatus(status, request);
            validateChunkIndex(request, status);

            StorageUploadResponse response = uploadChunkInternal(request);

            status.getCids().add(response.getCid());
            status.setUploadedChunks(status.getUploadedChunks() + 1);

            log.info("Uploaded chunk {}/{} for uploadId={}, cid={}",
                    status.getUploadedChunks(), status.getTotalChunks(),
                    request.getUploadId(), response.getCid());

            return status;
        }
    }

    @NonNull
    public ChunkedUploadStatus completeChunkedUpload(@NonNull String uploadId) {
        ChunkedUploadStatus status = sessions.get(uploadId);
        if (status == null) {
            throw new StorageException(StorageErrorCode.SESSION_NOT_FOUND,
                    "Upload session not found: " + uploadId);
        }

        Object lock = sessionLocks.get(uploadId);
        if (lock == null) {
            throw new StorageException(StorageErrorCode.SESSION_ALREADY_COMPLETED,
                    "Upload session already completed: " + uploadId);
        }

        synchronized (lock) {
            if (status.getUploadedChunks() != status.getTotalChunks()) {
                throw new StorageException(StorageErrorCode.CHUNK_UPLOAD_INCOMPLETE,
                        "Not all chunks uploaded: " + status.getUploadedChunks() + "/" + status.getTotalChunks());
            }

            try {
                byte[] mergedData = mergeChunks(status);
                StorageUploadRequest finalRequest = buildFinalUploadRequest(uploadId, status, mergedData);
                StorageProvider provider = providerFactory.getProvider(status.getStorageType());
                StorageUploadResponse finalResponse = provider.upload(finalRequest);

                status.setFinalCid(finalResponse.getCid());
                status.setCompleted(true);

                storageService.persistPinRecord(finalResponse.getCid(), status.getStorageType(),
                        StorageConstants.PIN_STATUS_PINNED, finalResponse.getSizeBytes());

                cleanupSession(uploadId);

                log.info("Completed chunked upload: uploadId={}, finalCid={}", uploadId, finalResponse.getCid());
            } catch (Exception e) {
                log.error("Failed to complete chunked upload: uploadId={}", uploadId, e);
                throw new StorageException(StorageErrorCode.UPLOAD_FAILED, status.getStorageType(), null,
                        "Failed to complete chunked upload: " + e.getMessage(), e);
            }
            return status;
        }
    }

    @NonNull
    public ChunkedUploadStatus getChunkedUploadStatus(@NonNull String uploadId) {
        ChunkedUploadStatus status = sessions.get(uploadId);
        if (status == null) {
            throw new StorageException(StorageErrorCode.SESSION_NOT_FOUND,
                    "Upload session not found: " + uploadId);
        }
        return status;
    }

    public void abortChunkedUpload(@NonNull String uploadId) {
        ChunkedUploadStatus status = sessions.remove(uploadId);
        Object lock = sessionLocks.remove(uploadId);
        if (status == null || lock == null) {
            throw new StorageException(StorageErrorCode.SESSION_NOT_FOUND,
                    "Upload session not found: " + uploadId);
        }

        cleanupChunks(status);
        log.info("Aborted chunked upload: uploadId={}", uploadId);
    }

    public void streamDownload(@NonNull String cid, @NonNull String storageType,
                               @NonNull OutputStream outputStream) throws IOException {
        providerFactory.getProvider(storageType).streamDownload(cid, outputStream);
    }

    private void validateSessionStatus(ChunkedUploadStatus status, ChunkedUploadRequest request) {
        if (status.isCompleted()) {
            throw new StorageException(StorageErrorCode.SESSION_ALREADY_COMPLETED,
                    "Upload session already completed: " + request.getUploadId());
        }
    }

    private void validateChunkIndex(ChunkedUploadRequest request, ChunkedUploadStatus status) {
        int expectedIndex = status.getUploadedChunks();
        if (request.getChunkIndex() != expectedIndex) {
            throw new StorageException(StorageErrorCode.INVALID_CHUNK_INDEX,
                    "Invalid chunk index. Expected: " + expectedIndex + ", actual: " + request.getChunkIndex());
        }
        if (request.getChunkData() == null || request.getChunkData().length == 0) {
            throw new StorageException(StorageErrorCode.INVALID_REQUEST,
                    "Chunk data cannot be empty");
        }
    }

    private StorageUploadResponse uploadChunkInternal(ChunkedUploadRequest request) {
        StorageUploadRequest uploadRequest = StorageUploadRequest.builder()
                .data(request.getChunkData())
                .fileName(request.getFileName() + ".part" + request.getChunkIndex())
                .storageType(request.getStorageType())
                .pin(request.isPin())
                .build();
        StorageProvider provider = providerFactory.getProvider(request.getStorageType());
        return provider.upload(uploadRequest);
    }

    private byte[] mergeChunks(ChunkedUploadStatus status) throws IOException {
        StorageProvider provider = providerFactory.getProvider(status.getStorageType());
        List<String> cids = status.getCids();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(estimateTotalSize(cids.size()));
        for (String cid : cids) {
            byte[] chunkData = provider.download(cid);
            baos.write(chunkData);
        }
        return baos.toByteArray();
    }

    private int estimateTotalSize(int chunkCount) {
        return chunkCount * storageConfig.getChunkSize();
    }

    private StorageUploadRequest buildFinalUploadRequest(String uploadId, ChunkedUploadStatus status, byte[] mergedData) {
        return StorageUploadRequest.builder()
                .data(mergedData)
                .fileName("merged_" + uploadId)
                .storageType(status.getStorageType())
                .pin(true)
                .build();
    }

    private void cleanupSession(String uploadId) {
        sessionLocks.remove(uploadId);
    }

    private void cleanupChunks(ChunkedUploadStatus status) {
        StorageProvider provider = providerFactory.getProvider(status.getStorageType());
        for (String cid : status.getCids()) {
            try {
                provider.unpin(cid);
                log.info("Cleaned up chunk cid={} for aborted upload", cid);
            } catch (Exception e) {
                log.warn("Failed to clean up chunk cid={}", cid, e);
            }
        }
    }
}
