package com.contractai.tenant.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.tenant.entity.TenantConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TenantConfigMapper extends BaseMapper<TenantConfig> {

    @Select("SELECT COALESCE(MAX(version), 0) FROM tenant_config WHERE tenant_id = #{tenantId} AND config_id = #{configId} AND namespace = #{namespace}")
    Integer selectMaxVersion(@Param("tenantId") Long tenantId, @Param("configId") String configId, @Param("namespace") String namespace);

    @Select("SELECT * FROM tenant_config WHERE tenant_id = #{tenantId} AND config_id = #{configId} AND namespace = #{namespace} AND enabled = 1 AND deleted = 0 ORDER BY version DESC LIMIT 1")
    TenantConfig selectLatestEnabled(@Param("tenantId") Long tenantId, @Param("configId") String configId, @Param("namespace") String namespace);
}
