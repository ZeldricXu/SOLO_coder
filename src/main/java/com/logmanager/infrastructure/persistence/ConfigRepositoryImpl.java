package com.logmanager.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.logmanager.common.enums.ConfigSource;
import com.logmanager.domain.model.ConfigDefinition;
import com.logmanager.domain.repository.ConfigRepository;
import com.logmanager.infrastructure.persistence.entity.ConfigDefinitionPO;
import com.logmanager.infrastructure.persistence.mapper.ConfigDefinitionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ConfigRepositoryImpl implements ConfigRepository {

    private final ConfigDefinitionMapper mapper;

    @Override
    public Mono<ConfigDefinition> save(ConfigDefinition config) {
        ConfigDefinitionPO po = toPO(config);
        if (po.getId() == null) {
            po.setId(UUID.randomUUID().toString());
        }
        mapper.insert(po);
        return Mono.just(toDomain(po));
    }

    @Override
    public Mono<ConfigDefinition> findById(String configId) {
        ConfigDefinitionPO po = mapper.selectById(configId);
        return po != null ? Mono.just(toDomain(po)) : Mono.empty();
    }

    @Override
    public Mono<ConfigDefinition> findByNamespaceAndKey(String namespace, String key) {
        LambdaQueryWrapper<ConfigDefinitionPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConfigDefinitionPO::getNamespace, namespace)
                .eq(ConfigDefinitionPO::getConfigId, key);
        ConfigDefinitionPO po = mapper.selectOne(wrapper);
        return po != null ? Mono.just(toDomain(po)) : Mono.empty();
    }

    @Override
    public Flux<ConfigDefinition> findByNamespace(String namespace) {
        LambdaQueryWrapper<ConfigDefinitionPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConfigDefinitionPO::getNamespace, namespace);
        List<ConfigDefinitionPO> pos = mapper.selectList(wrapper);
        return Flux.fromIterable(pos).map(this::toDomain);
    }

    @Override
    public Mono<Void> deleteById(String configId) {
        mapper.deleteById(configId);
        return Mono.empty();
    }

    @Override
    public Flux<ConfigDefinition> findAllEnabled() {
        LambdaQueryWrapper<ConfigDefinitionPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConfigDefinitionPO::getEnabled, true);
        List<ConfigDefinitionPO> pos = mapper.selectList(wrapper);
        return Flux.fromIterable(pos).map(this::toDomain);
    }

    private ConfigDefinitionPO toPO(ConfigDefinition domain) {
        ConfigDefinitionPO po = new ConfigDefinitionPO();
        po.setId(domain.getId());
        po.setConfigId(domain.getConfigId());
        po.setNamespace(domain.getNamespace());
        po.setVersion(domain.getVersion());
        po.setParameters(domain.getParameters());
        po.setEnabled(domain.getEnabled());
        po.setAppliedAt(domain.getAppliedAt());
        po.setSource(domain.getSource() != null ? domain.getSource().getCode() : null);
        po.setAttributes(domain.getAttributes());
        po.setCreatedAt(domain.getCreatedAt());
        po.setUpdatedAt(domain.getUpdatedAt());
        return po;
    }

    private ConfigDefinition toDomain(ConfigDefinitionPO po) {
        ConfigDefinition domain = new ConfigDefinition();
        domain.setId(po.getId());
        domain.setConfigId(po.getConfigId());
        domain.setNamespace(po.getNamespace());
        domain.setVersion(po.getVersion());
        domain.setParameters(po.getParameters());
        domain.setEnabled(po.getEnabled());
        domain.setAppliedAt(po.getAppliedAt());
        domain.setSource(po.getSource() != null ? ConfigSource.valueOf(po.getSource().toUpperCase()) : null);
        domain.setAttributes(po.getAttributes());
        domain.setCreatedAt(po.getCreatedAt());
        domain.setUpdatedAt(po.getUpdatedAt());
        return domain;
    }
}
