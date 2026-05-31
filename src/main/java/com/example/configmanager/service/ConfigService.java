package com.example.configmanager.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.configmanager.dto.ConfigCreateDTO;
import com.example.configmanager.dto.ConfigQueryDTO;
import com.example.configmanager.dto.ConfigRollbackDTO;
import com.example.configmanager.dto.ConfigUpdateDTO;
import com.example.configmanager.entity.Config;
import com.example.configmanager.entity.ConfigVersion;

import java.util.List;

public interface ConfigService {

    Config createConfig(ConfigCreateDTO dto);

    Config updateConfig(Long id, ConfigUpdateDTO dto);

    Config getConfig(Long id);

    IPage<Config> listConfigs(ConfigQueryDTO dto);

    Config rollbackConfig(Long id, ConfigRollbackDTO dto);

    void deleteConfig(Long id);

    List<ConfigVersion> getConfigHistory(Long id);
}
