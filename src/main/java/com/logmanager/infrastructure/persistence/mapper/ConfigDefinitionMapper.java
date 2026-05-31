package com.logmanager.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.logmanager.infrastructure.persistence.entity.ConfigDefinitionPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConfigDefinitionMapper extends BaseMapper<ConfigDefinitionPO> {
}
