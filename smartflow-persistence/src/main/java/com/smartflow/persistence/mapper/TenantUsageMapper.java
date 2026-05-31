package com.smartflow.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartflow.persistence.entity.TenantUsage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantUsageMapper extends BaseMapper<TenantUsage> {
}
