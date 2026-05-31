package com.contractai.tenant.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.tenant.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {
}
