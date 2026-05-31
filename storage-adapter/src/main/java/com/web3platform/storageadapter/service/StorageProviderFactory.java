package com.web3platform.storageadapter.service;

import com.web3platform.storageadapter.config.StorageConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class StorageProviderFactory {

    private final StorageConfig storageConfig;

    private final Map<StorageType, StorageProvider> providers = new EnumMap<>(StorageType.class);

    @PostConstruct
    public void init() {
        providers.put(StorageType.IPFS, new IpfsStorageProvider(storageConfig.getIpfsApiUrl()));
        providers.put(StorageType.ARWEAVE, new ArweaveStorageProvider(storageConfig.getArweaveGatewayUrl()));
    }

    public StorageProvider getProvider(String storageType) {
        StorageType type = StorageType.valueOf(storageType.toUpperCase());
        StorageProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported storage type: " + storageType);
        }
        return provider;
    }

    public enum StorageType {
        IPFS, ARWEAVE
    }
}
