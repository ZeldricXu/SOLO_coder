package com.contractai.tenant.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.tenant.entity.TenantQuota;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TenantQuotaMapper extends BaseMapper<TenantQuota> {

    @Update("UPDATE tenant_quota SET quota_used = quota_used + #{amount}, updated_at = NOW() WHERE id = #{id} AND quota_used + #{amount} <= quota_limit")
    int atomicConsume(@Param("id") Long id, @Param("amount") Long amount);

    @Update("UPDATE tenant_quota SET quota_used = 0, last_reset_at = NOW(), updated_at = NOW() WHERE id = #{id}")
    int resetQuota(@Param("id") Long id);
}
