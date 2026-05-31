package com.chain.infrastructure.storageadapter.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chain.infrastructure.common.util.JsonUtils;
import com.chain.infrastructure.persistence.entity.StorageObject;
import com.chain.infrastructure.persistence.mapper.StorageObjectMapper;
import com.chain.infrastructure.storageadapter.dto.StoreRequest;
import com.chain.infrastructure.storageadapter.dto.StoreResult;
import com.chain.infrastructure.storageadapter.provider.StorageProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final Map<String, StorageProvider> providers;
    private final StorageObjectMapper storageObjectMapper;

    public StorageService(List<StorageProvider> providerList, StorageObjectMapper storageObjectMapper) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(p -> p.getName().toUpperCase(), Function.identity()));
        this.storageObjectMapper = storageObjectMapper;
    }

    public Mono<StoreResult> store(StoreRequest request) {
        StorageProvider provider = getProvider(request.getStorageNetwork());

        return provider.store(request)
                .flatMap(result -> {
                    StorageObject obj = new StorageObject();
                    obj.setObjectId(result.getObjectId());
                    obj.setStorageNetwork(result.getStorageNetwork());
                    obj.setCid(result.getCid());
                    obj.setContentHash(result.getContentHash());
                    obj.setContentType(result.getContentType());
                    obj.setSize(result.getSize());
                    obj.setPinStatus(result.getPinStatus());
                    obj.setMetadata(result.getMetadata() != null ? JsonUtils.toJson(result.getMetadata()) : null);
                    obj.setOriginalUrl(result.getGatewayUrl());
                    storageObjectMapper.insert(obj);
                    return Mono.just(result);
                });
    }

    public Mono<byte[]> retrieve(String storageNetwork, String cid) {
        StorageProvider provider = getProvider(storageNetwork);
        return provider.retrieve(cid);
    }

    public Mono<Boolean> pin(String storageNetwork, String cid) {
        StorageProvider provider = getProvider(storageNetwork);
        return provider.pin(cid)
                .flatMap(success -> {
                    if (success) {
                        QueryWrapper<StorageObject> wrapper = new QueryWrapper<>();
                        wrapper.eq("storage_network", storageNetwork)
                                .eq("cid", cid);
                        StorageObject obj = storageObjectMapper.selectOne(wrapper);
                        if (obj != null) {
                            obj.setPinStatus("PINNED");
                            storageObjectMapper.updateById(obj);
                        }
                    }
                    return Mono.just(success);
                });
    }

    public Mono<Boolean> unpin(String storageNetwork, String cid) {
        StorageProvider provider = getProvider(storageNetwork);
        return provider.unpin(cid)
                .flatMap(success -> {
                    if (success) {
                        QueryWrapper<StorageObject> wrapper = new QueryWrapper<>();
                        wrapper.eq("storage_network", storageNetwork)
                                .eq("cid", cid);
                        StorageObject obj = storageObjectMapper.selectOne(wrapper);
                        if (obj != null) {
                            obj.setPinStatus("UNPINNED");
                            storageObjectMapper.updateById(obj);
                        }
                    }
                    return Mono.just(success);
                });
    }

    public Mono<StorageObject> getObject(String objectId) {
        return Mono.justOrEmpty(storageObjectMapper.selectById(objectId));
    }

    public Mono<StorageObject> getObjectByCid(String storageNetwork, String cid) {
        return Mono.fromCallable(() -> {
            QueryWrapper<StorageObject> wrapper = new QueryWrapper<>();
            wrapper.eq("storage_network", storageNetwork)
                    .eq("cid", cid);
            return storageObjectMapper.selectOne(wrapper);
        });
    }

    private StorageProvider getProvider(String network) {
        StorageProvider provider = providers.get(network.toUpperCase());
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported storage network: " + network);
        }
        return provider;
    }
}
