package com.smartflow.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartflow.persistence.entity.TenantConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantConfigMapper extends BaseMapper<TenantConfig> {
}
