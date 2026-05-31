package com.taskflow.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.data.entity.ConfigEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConfigMapper extends BaseMapper<ConfigEntity> {

    @Select("SELECT * FROM config WHERE tenant_id = #{tenantId} AND config_id = #{configId} ORDER BY version DESC LIMIT 1")
    ConfigEntity selectLatestByTenantAndId(@Param("tenantId") String tenantId, @Param("configId") String configId);

    @Select("SELECT * FROM config WHERE tenant_id = #{tenantId} AND namespace = #{namespace} AND config_id = #{configId} AND enabled = 1 ORDER BY version DESC LIMIT 1")
    ConfigEntity selectEnabledByNamespace(@Param("tenantId") String tenantId, @Param("namespace") String namespace, @Param("configId") String configId);
}
