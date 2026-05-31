package com.web3platform.storageadapter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.web3platform.persistence.mapper.StoragePinMapper;
import com.web3platform.persistence.model.entity.StoragePin;
import com.web3platform.storageadapter.constant.StorageConstants;
import com.web3platform.storageadapter.exception.StorageErrorCode;
import com.web3platform.storageadapter.exception.StorageException;
import com.web3platform.storageadapter.model.ContentInfo;
import com.web3platform.storageadapter.model.PinStatus;
import com.web3platform.storageadapter.model.StorageUploadRequest;
import com.web3platform.storageadapter.model.StorageUploadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final StorageProviderFactory providerFactory;
    private final StoragePinMapper storagePinMapper;

    @NonNull
    public StorageUploadResponse upload(@NonNull StorageUploadRequest request) {
        StorageProvider provider = providerFactory.getProvider(request.getStorageType());
        StorageUploadResponse response = provider.upload(request);
        persistPinRecord(response.getCid(), response.getStorageType(),
                request.isPin() ? StorageConstants.PIN_STATUS_PINNED : StorageConstants.PIN_STATUS_UNPINNED,
                response.getSizeBytes());
        return response;
    }

    @NonNull
    public byte[] download(@NonNull String cid) {
        return resolveProviderByCid(cid).download(cid);
    }

    @NonNull
    public PinStatus pin(@NonNull String cid, @NonNull String storageType) {
        StorageProvider provider = providerFactory.getProvider(storageType);
        PinStatus pinStatus = provider.pin(cid);
        String status = StorageConstants.PIN_STATUS_PINNED.equals(pinStatus.getStatus())
                ? StorageConstants.PIN_STATUS_PINNED
                : StorageConstants.PIN_STATUS_PINNING;
        updatePinRecord(cid, storageType, status);
        return pinStatus;
    }

    @NonNull
    public PinStatus unpin(@NonNull String cid, @NonNull String storageType) {
        StorageProvider provider = providerFactory.getProvider(storageType);
        PinStatus pinStatus = provider.unpin(cid);
        updatePinRecord(cid, storageType, StorageConstants.PIN_STATUS_UNPINNED);
        return pinStatus;
    }

    @NonNull
    public ContentInfo getStatus(@NonNull String cid, @NonNull String storageType) {
        return providerFactory.getProvider(storageType).getStatus(cid);
    }

    public void streamDownload(@NonNull String cid, @NonNull String storageType,
                               @NonNull OutputStream outputStream) throws IOException {
        providerFactory.getProvider(storageType).streamDownload(cid, outputStream);
    }

    private StorageProvider resolveProviderByCid(String cid) {
        String storageType = cid.startsWith(StorageConstants.CID_PREFIX_IPFS)
                ? StorageProviderFactory.StorageType.IPFS.name()
                : StorageProviderFactory.StorageType.ARWEAVE.name();
        return providerFactory.getProvider(storageType);
    }

    void persistPinRecord(String cid, String storageType, String pinStatus, Long sizeBytes) {
        LambdaQueryWrapper<StoragePin> query = new LambdaQueryWrapper<StoragePin>().eq(StoragePin::getCid, cid);
        StoragePin existing = storagePinMapper.selectOne(query);

        if (existing != null) {
            updatePinRecord(existing, pinStatus, sizeBytes);
            storagePinMapper.updateById(existing);
        } else {
            StoragePin record = buildNewPinRecord(cid, storageType, pinStatus, sizeBytes);
            storagePinMapper.insert(record);
        }
    }

    private void updatePinRecord(String cid, String storageType, String pinStatus) {
        LambdaQueryWrapper<StoragePin> query = new LambdaQueryWrapper<StoragePin>().eq(StoragePin::getCid, cid);
        StoragePin existing = storagePinMapper.selectOne(query);

        if (existing != null) {
            updatePinRecord(existing, pinStatus, null);
            storagePinMapper.updateById(existing);
        } else {
            StoragePin record = buildNewPinRecord(cid, storageType, pinStatus, null);
            storagePinMapper.insert(record);
        }
    }

    private void updatePinRecord(StoragePin record, String pinStatus, Long sizeBytes) {
        record.setPinStatus(pinStatus);
        record.setUpdatedAt(LocalDateTime.now());
        if (sizeBytes != null) {
            record.setSizeBytes(sizeBytes);
        }
        if (StorageConstants.PIN_STATUS_PINNED.equals(pinStatus)) {
            record.setPinnedAt(LocalDateTime.now());
        }
    }

    private StoragePin buildNewPinRecord(String cid, String storageType, String pinStatus, Long sizeBytes) {
        StoragePin record = new StoragePin();
        record.setCid(cid);
        record.setStorageType(storageType);
        record.setPinStatus(pinStatus);
        if (sizeBytes != null) {
            record.setSizeBytes(sizeBytes);
        }
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        if (StorageConstants.PIN_STATUS_PINNED.equals(pinStatus)) {
            record.setPinnedAt(LocalDateTime.now());
        }
        return record;
    }
}
