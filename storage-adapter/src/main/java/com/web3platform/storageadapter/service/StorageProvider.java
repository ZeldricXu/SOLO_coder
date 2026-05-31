package com.web3platform.storageadapter.service;

import com.web3platform.storageadapter.model.ContentInfo;
import com.web3platform.storageadapter.model.PinStatus;
import com.web3platform.storageadapter.model.StorageUploadRequest;
import com.web3platform.storageadapter.model.StorageUploadResponse;

import java.io.IOException;
import java.io.OutputStream;

public interface StorageProvider {

    StorageUploadResponse upload(StorageUploadRequest request);

    byte[] download(String cid);

    PinStatus pin(String cid);

    PinStatus unpin(String cid);

    ContentInfo getStatus(String cid);

    default void streamDownload(String cid, OutputStream outputStream) throws IOException {
        byte[] data = download(cid);
        outputStream.write(data);
        outputStream.flush();
    }
}
