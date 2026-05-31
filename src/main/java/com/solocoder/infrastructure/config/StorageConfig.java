package com.solocoder.infrastructure.config;

import com.solocoder.domain.port.StoragePort;
import com.solocoder.infrastructure.adapter.storage.LocalStorageAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class StorageConfig {

    @Bean("masterStorage")
    public StoragePort masterStorage(
            @Value("${storage.master.base-path:/data/storage/master}") String masterBasePath) throws IOException {
        LocalStorageAdapter adapter = new LocalStorageAdapter();
        adapter.setBasePath(masterBasePath);
        adapter.init();
        return adapter;
    }

    @Bean("replicaStorage")
    public StoragePort replicaStorage(
            @Value("${storage.replica.base-path:/data/storage/replica}") String replicaBasePath) throws IOException {
        LocalStorageAdapter adapter = new LocalStorageAdapter();
        adapter.setBasePath(replicaBasePath);
        adapter.init();
        return adapter;
    }
}
