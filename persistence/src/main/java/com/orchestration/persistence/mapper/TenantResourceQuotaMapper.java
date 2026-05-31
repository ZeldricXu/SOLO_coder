package com.orchestration.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orchestration.persistence.entity.TenantResourceQuota;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantResourceQuotaMapper extends BaseMapper<TenantResourceQuota> {
}
