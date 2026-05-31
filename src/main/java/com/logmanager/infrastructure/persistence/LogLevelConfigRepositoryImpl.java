package com.logmanager.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.logmanager.common.enums.LogLevel;
import com.logmanager.domain.model.LogLevelConfig;
import com.logmanager.domain.repository.LogLevelConfigRepository;
import com.logmanager.infrastructure.persistence.entity.LogLevelConfigPO;
import com.logmanager.infrastructure.persistence.mapper.LogLevelConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class LogLevelConfigRepositoryImpl implements LogLevelConfigRepository {

    private final LogLevelConfigMapper mapper;

    @Override
    public Mono<LogLevelConfig> save(LogLevelConfig config) {
        LogLevelConfigPO po = toPO(config);
        if (po.getId() == null) {
            po.setId(UUID.randomUUID().toString());
        }
        mapper.insert(po);
        return Mono.just(toDomain(po));
    }

    @Override
    public Mono<LogLevelConfig> findById(String id) {
        LogLevelConfigPO po = mapper.selectById(id);
        return po != null ? Mono.just(toDomain(po)) : Mono.empty();
    }

    @Override
    public Flux<LogLevelConfig> findByServiceName(String serviceName) {
        LambdaQueryWrapper<LogLevelConfigPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LogLevelConfigPO::getServiceName, serviceName);
        List<LogLevelConfigPO> pos = mapper.selectList(wrapper);
        return Flux.fromIterable(pos).map(this::toDomain);
    }

    @Override
    public Flux<LogLevelConfig> findActiveByServiceName(String serviceName) {
        LambdaQueryWrapper<LogLevelConfigPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LogLevelConfigPO::getServiceName, serviceName)
                .eq(LogLevelConfigPO::getActive, true);
        List<LogLevelConfigPO> pos = mapper.selectList(wrapper);
        return Flux.fromIterable(pos).map(this::toDomain);
    }

    @Override
    public Mono<Void> deleteById(String id) {
        mapper.deleteById(id);
        return Mono.empty();
    }

    @Override
    public Flux<LogLevelConfig> findAllActive() {
        LambdaQueryWrapper<LogLevelConfigPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LogLevelConfigPO::getActive, true);
        List<LogLevelConfigPO> pos = mapper.selectList(wrapper);
        return Flux.fromIterable(pos).map(this::toDomain);
    }

    private LogLevelConfigPO toPO(LogLevelConfig domain) {
        LogLevelConfigPO po = new LogLevelConfigPO();
        po.setId(domain.getId());
        po.setServiceName(domain.getServiceName());
        po.setLoggerName(domain.getLoggerName());
        po.setCurrentLevel(domain.getCurrentLevel() != null ? domain.getCurrentLevel().getDisplayName() : null);
        po.setTargetLevel(domain.getTargetLevel() != null ? domain.getTargetLevel().getDisplayName() : null);
        po.setEffectiveAt(domain.getEffectiveAt());
        po.setExpiresAt(domain.getExpiresAt());
        po.setReason(domain.getReason());
        po.setOperator(domain.getOperator());
        po.setActive(domain.getActive());
        po.setAttributes(domain.getAttributes());
        po.setCreatedAt(domain.getCreatedAt());
        po.setUpdatedAt(domain.getUpdatedAt());
        return po;
    }

    private LogLevelConfig toDomain(LogLevelConfigPO po) {
        LogLevelConfig domain = new LogLevelConfig();
        domain.setId(po.getId());
        domain.setServiceName(po.getServiceName());
        domain.setLoggerName(po.getLoggerName());
        domain.setCurrentLevel(po.getCurrentLevel() != null ? LogLevel.fromString(po.getCurrentLevel()) : null);
        domain.setTargetLevel(po.getTargetLevel() != null ? LogLevel.fromString(po.getTargetLevel()) : null);
        domain.setEffectiveAt(po.getEffectiveAt());
        domain.setExpiresAt(po.getExpiresAt());
        domain.setReason(po.getReason());
        domain.setOperator(po.getOperator());
        domain.setActive(po.getActive());
        domain.setAttributes(po.getAttributes());
        domain.setCreatedAt(po.getCreatedAt());
        domain.setUpdatedAt(po.getUpdatedAt());
        return domain;
    }
}
