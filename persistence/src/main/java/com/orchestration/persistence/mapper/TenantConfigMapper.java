package com.orchestration.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orchestration.persistence.entity.TenantConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantConfigMapper extends BaseMapper<TenantConfig> {
}
