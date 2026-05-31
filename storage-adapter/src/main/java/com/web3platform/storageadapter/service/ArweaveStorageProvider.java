package com.web3platform.storageadapter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web3platform.storageadapter.constant.StorageConstants;
import com.web3platform.storageadapter.exception.StorageErrorCode;
import com.web3platform.storageadapter.exception.StorageException;
import com.web3platform.storageadapter.model.ContentInfo;
import com.web3platform.storageadapter.model.PinStatus;
import com.web3platform.storageadapter.model.StorageUploadRequest;
import com.web3platform.storageadapter.model.StorageUploadResponse;
import com.web3platform.storageadapter.util.HttpClientFactory;
import com.web3platform.storageadapter.util.StreamBufferPool;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

@Slf4j
public class ArweaveStorageProvider implements StorageProvider {

    private static final String STORAGE_TYPE = StorageProviderFactory.StorageType.ARWEAVE.name();
    private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();
    private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();

    private final String gatewayUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ArweaveStorageProvider(String gatewayUrl) {
        this.gatewayUrl = HttpClientFactory.normalizeBaseUrl(gatewayUrl);
        this.httpClient = HttpClientFactory.getSharedHttpClient();
        this.objectMapper = HttpClientFactory.getSharedObjectMapper();
    }

    @Override
    public StorageUploadResponse upload(@NonNull StorageUploadRequest request) {
        validateUploadRequest(request);

        String txId = generateTxId();
        String jsonBody = serializeTxRequest(txId, request);
        RequestBody body = RequestBody.create(jsonBody, HttpClientFactory.json());
        Request httpRequest = buildPostRequest(StorageConstants.ARWEAVE_TX_PATH, body);

        try (Response response = executeRequest(httpRequest)) {
            log.debug("Arweave upload success: cid={}, size={}", txId, request.getData().length);
            return StorageUploadResponse.builder()
                    .cid(txId)
                    .storageType(STORAGE_TYPE)
                    .sizeBytes(request.getData().length)
                    .pinned(request.isPin())
                    .uploadedAt(LocalDateTime.now())
                    .build();
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.UPLOAD_FAILED, STORAGE_TYPE, txId,
                    "Arweave upload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] download(@NonNull String cid) {
        Request httpRequest = buildGetRequest(String.format(StorageConstants.ARWEAVE_TX_DATA_PATH, cid));
        try (Response response = executeRequest(httpRequest)) {
            String base64Data = response.body().string();
            return BASE64_DECODER.decode(base64Data);
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.DOWNLOAD_FAILED, STORAGE_TYPE, cid,
                    "Arweave download failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void streamDownload(@NonNull String cid, @NonNull OutputStream outputStream) throws IOException {
        Request httpRequest = buildGetRequest(String.format(StorageConstants.ARWEAVE_TX_DATA_PATH, cid));
        try (Response response = executeRequest(httpRequest);
             InputStream inputStream = response.body().byteStream()) {

            byte[] buffer = StreamBufferPool.borrowBuffer();
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
            log.debug("Arweave stream download completed: cid={}", cid);
        }
    }

    @Override
    public PinStatus pin(@NonNull String cid) {
        String jsonBody = serializePinRequest(cid);
        RequestBody body = RequestBody.create(jsonBody, HttpClientFactory.json());
        Request httpRequest = buildPostRequest(StorageConstants.ARWEAVE_PIN_PATH, body);

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String status = response.isSuccessful()
                    ? StorageConstants.PIN_STATUS_PINNED
                    : StorageConstants.PIN_STATUS_FAILED;
            log.debug("Arweave pin result: cid={}, status={}", cid, status);
            return PinStatus.builder()
                    .cid(cid)
                    .status(status)
                    .storageType(STORAGE_TYPE)
                    .build();
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.PIN_FAILED, STORAGE_TYPE, cid,
                    "Arweave pin failed: " + e.getMessage(), e);
        }
    }

    @Override
    public PinStatus unpin(@NonNull String cid) {
        String jsonBody = serializePinRequest(cid);
        RequestBody body = RequestBody.create(jsonBody, HttpClientFactory.json());
        Request httpRequest = new Request.Builder()
                .url(gatewayUrl + String.format(StorageConstants.ARWEAVE_PIN_DELETE_PATH, cid))
                .delete(body)
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String status = response.isSuccessful()
                    ? StorageConstants.PIN_STATUS_UNPINNED
                    : StorageConstants.PIN_STATUS_FAILED;
            log.debug("Arweave unpin result: cid={}, status={}", cid, status);
            return PinStatus.builder()
                    .cid(cid)
                    .status(status)
                    .storageType(STORAGE_TYPE)
                    .build();
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.UNPIN_FAILED, STORAGE_TYPE, cid,
                    "Arweave unpin failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ContentInfo getStatus(@NonNull String cid) {
        Request httpRequest = buildGetRequest(String.format(StorageConstants.ARWEAVE_TX_PATH + "/%s", cid));
        String pinStatusValue = "unknown";
        long sizeBytes = 0;
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                JsonNode json = objectMapper.readTree(responseBody);
                sizeBytes = json.has("data_size") ? json.get("data_size").asLong() : 0;
                pinStatusValue = StorageConstants.PIN_STATUS_PINNED;
            }
            return ContentInfo.builder()
                    .cid(cid)
                    .storageType(STORAGE_TYPE)
                    .sizeBytes(sizeBytes)
                    .pinStatus(pinStatusValue)
                    .retrievedAt(LocalDateTime.now())
                    .build();
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.GET_STATUS_FAILED, STORAGE_TYPE, cid,
                    "Arweave get status failed: " + e.getMessage(), e);
        }
    }

    private void validateUploadRequest(StorageUploadRequest request) {
        if (request.getData() == null) {
            throw new StorageException(StorageErrorCode.INVALID_REQUEST, "Upload data cannot be null");
        }
    }

    private String generateTxId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String serializeTxRequest(String txId, StorageUploadRequest request) {
        try {
            var txNode = objectMapper.createObjectNode();
            txNode.put("id", txId);
            txNode.put("data", BASE64_ENCODER.encodeToString(request.getData()));
            if (request.getFileName() != null) {
                var tagsNode = txNode.putArray("tags");
                var tag = tagsNode.addObject();
                tag.put("name", "FileName");
                tag.put("value", request.getFileName());
            }
            return objectMapper.writeValueAsString(txNode);
        } catch (Exception e) {
            throw new StorageException(StorageErrorCode.UPLOAD_FAILED,
                    "Arweave upload serialization error", e);
        }
    }

    private String serializePinRequest(String cid) {
        try {
            var node = objectMapper.createObjectNode();
            node.put("txid", cid);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new StorageException(StorageErrorCode.PIN_FAILED,
                    "Arweave pin serialization error", e);
        }
    }

    private Request buildPostRequest(String apiPath, RequestBody body) {
        return new Request.Builder()
                .url(gatewayUrl + apiPath)
                .post(body)
                .build();
    }

    private Request buildGetRequest(String apiPath) {
        return new Request.Builder()
                .url(gatewayUrl + apiPath)
                .get()
                .build();
    }

    private Response executeRequest(Request request) throws IOException {
        Response response = httpClient.newCall(request).execute();
        if (!response.isSuccessful()) {
            String errorMsg = "HTTP " + response.code() + ": " + response.message();
            response.close();
            throw new StorageException(StorageErrorCode.UPLOAD_FAILED, errorMsg);
        }
        return response;
    }
}
