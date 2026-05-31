package com.iotplatform.config.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iotplatform.config.dto.ConfigCreateDTO;
import com.iotplatform.config.dto.ConfigRollbackDTO;
import com.iotplatform.config.dto.ConfigUpdateDTO;
import com.iotplatform.config.entity.SysConfig;
import com.iotplatform.config.entity.SysConfigHistory;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Optional;

public interface ConfigService {

    Mono<SysConfig> createConfig(ConfigCreateDTO dto);

    Mono<SysConfig> updateConfig(ConfigUpdateDTO dto);

    Mono<SysConfig> getConfig(String configId, String namespace);

    Mono<SysConfig> getConfigByVersion(String configId, String namespace, Integer version);

    Mono<Optional<SysConfig>> getConfigByKey(String namespace, String configKey);

    Mono<IPage<SysConfig>> listConfigs(String namespace, String configKey, Boolean enabled,
                                       Integer pageNum, Integer pageSize);

    Mono<List<SysConfigHistory>> getConfigHistory(String configId, String namespace);

    Mono<SysConfig> rollbackConfig(ConfigRollbackDTO dto);

    Mono<Void> deleteConfig(String configId, String namespace);

    Mono<Boolean> validateConfig(String configKey, String configValue);
}
