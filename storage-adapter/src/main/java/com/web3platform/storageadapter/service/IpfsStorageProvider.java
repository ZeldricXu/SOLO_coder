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

@Slf4j
public class IpfsStorageProvider implements StorageProvider {

    private static final String STORAGE_TYPE = StorageProviderFactory.StorageType.IPFS.name();

    private final String apiBaseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public IpfsStorageProvider(String apiBaseUrl) {
        this.apiBaseUrl = HttpClientFactory.normalizeBaseUrl(apiBaseUrl);
        this.httpClient = HttpClientFactory.getSharedHttpClient();
        this.objectMapper = HttpClientFactory.getSharedObjectMapper();
    }

    @Override
    public StorageUploadResponse upload(@NonNull StorageUploadRequest request) {
        validateUploadRequest(request);

        MultipartBody body = buildMultipartBody(request);
        Request httpRequest = buildPostRequest(StorageConstants.IPFS_API_ADD, body);

        try (Response response = executeRequest(httpRequest)) {
            JsonNode json = parseResponse(response);
            String cid = json.get("Hash").asText();
            long size = json.has("Size") ? json.get("Size").asLong() : request.getData().length;

            log.debug("IPFS upload success: cid={}, size={}", cid, size);
            return StorageUploadResponse.builder()
                    .cid(cid)
                    .storageType(STORAGE_TYPE)
                    .sizeBytes(size)
                    .pinned(request.isPin())
                    .uploadedAt(LocalDateTime.now())
                    .build();
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.UPLOAD_FAILED, STORAGE_TYPE, null,
                    "IPFS upload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] download(@NonNull String cid) {
        Request httpRequest = buildCatRequest(cid);
        try (Response response = executeRequest(httpRequest)) {
            return response.body().bytes();
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.DOWNLOAD_FAILED, STORAGE_TYPE, cid,
                    "IPFS download failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void streamDownload(@NonNull String cid, @NonNull OutputStream outputStream) throws IOException {
        Request httpRequest = buildCatRequest(cid);
        try (Response response = executeRequest(httpRequest);
             InputStream inputStream = response.body().byteStream()) {

            byte[] buffer = StreamBufferPool.borrowBuffer();
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
            log.debug("IPFS stream download completed: cid={}", cid);
        }
    }

    @Override
    public PinStatus pin(@NonNull String cid) {
        Request httpRequest = buildPinPostRequest(StorageConstants.IPFS_API_PIN_ADD, cid);
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String status = response.isSuccessful()
                    ? StorageConstants.PIN_STATUS_PINNED
                    : StorageConstants.PIN_STATUS_FAILED;
            log.debug("IPFS pin result: cid={}, status={}", cid, status);
            return PinStatus.builder()
                    .cid(cid)
                    .status(status)
                    .storageType(STORAGE_TYPE)
                    .build();
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.PIN_FAILED, STORAGE_TYPE, cid,
                    "IPFS pin failed: " + e.getMessage(), e);
        }
    }

    @Override
    public PinStatus unpin(@NonNull String cid) {
        Request httpRequest = buildPinPostRequest(StorageConstants.IPFS_API_PIN_RM, cid);
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String status = response.isSuccessful()
                    ? StorageConstants.PIN_STATUS_UNPINNED
                    : StorageConstants.PIN_STATUS_FAILED;
            log.debug("IPFS unpin result: cid={}, status={}", cid, status);
            return PinStatus.builder()
                    .cid(cid)
                    .status(status)
                    .storageType(STORAGE_TYPE)
                    .build();
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.UNPIN_FAILED, STORAGE_TYPE, cid,
                    "IPFS unpin failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ContentInfo getStatus(@NonNull String cid) {
        Request httpRequest = buildPinPostRequest(StorageConstants.IPFS_API_PIN_LS, cid);
        String pinStatusValue = StorageConstants.PIN_STATUS_UNPINNED;
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                JsonNode json = objectMapper.readTree(responseBody);
                JsonNode keys = json.get("Keys");
                if (keys != null && keys.has(cid)) {
                    JsonNode pinInfo = keys.get(cid);
                    pinStatusValue = pinInfo.has("Type") ? pinInfo.get("Type").asText()
                            : StorageConstants.PIN_STATUS_PINNED;
                }
            }
            return ContentInfo.builder()
                    .cid(cid)
                    .storageType(STORAGE_TYPE)
                    .sizeBytes(0)
                    .pinStatus(pinStatusValue)
                    .retrievedAt(LocalDateTime.now())
                    .build();
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.GET_STATUS_FAILED, STORAGE_TYPE, cid,
                    "IPFS get status failed: " + e.getMessage(), e);
        }
    }

    private void validateUploadRequest(StorageUploadRequest request) {
        if (request.getData() == null) {
            throw new StorageException(StorageErrorCode.INVALID_REQUEST, "Upload data cannot be null");
        }
    }

    private MultipartBody buildMultipartBody(StorageUploadRequest request) {
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", request.getFileName(),
                        RequestBody.create(request.getData(), HttpClientFactory.octetStream()));
        if (request.isPin()) {
            builder.addFormDataPart("pin", "true");
        }
        return builder.build();
    }

    private Request buildPostRequest(String apiPath, RequestBody body) {
        return new Request.Builder()
                .url(apiBaseUrl + apiPath)
                .post(body)
                .build();
    }

    private Request buildCatRequest(String cid) {
        return buildPostRequest(StorageConstants.IPFS_API_CAT + cid, HttpClientFactory.emptyBody());
    }

    private Request buildPinPostRequest(String apiPath, String cid) {
        return buildPostRequest(apiPath + cid, HttpClientFactory.emptyBody());
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

    private JsonNode parseResponse(Response response) throws IOException {
        String responseBody = response.body().string();
        return objectMapper.readTree(responseBody);
    }
}
