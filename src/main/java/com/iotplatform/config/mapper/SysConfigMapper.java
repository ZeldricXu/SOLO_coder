package com.iotplatform.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iotplatform.config.entity.SysConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.Optional;

@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {

    @Select("SELECT * FROM sys_config WHERE config_id = #{configId} AND namespace = #{namespace} " +
            "AND deleted = 0 ORDER BY version DESC LIMIT 1")
    Optional<SysConfig> findLatest(@Param("configId") String configId,
                                   @Param("namespace") String namespace);

    @Select("SELECT * FROM sys_config WHERE config_id = #{configId} AND namespace = #{namespace} " +
            "AND version = #{version} AND deleted = 0")
    Optional<SysConfig> findByVersion(@Param("configId") String configId,
                                      @Param("namespace") String namespace,
                                      @Param("version") Integer version);

    @Select("SELECT * FROM sys_config WHERE namespace = #{namespace} AND config_key = #{configKey} " +
            "AND deleted = 0 AND enabled = 1 ORDER BY version DESC LIMIT 1")
    Optional<SysConfig> findByNamespaceAndKey(@Param("namespace") String namespace,
                                              @Param("configKey") String configKey);

    @Select("SELECT * FROM sys_config WHERE namespace = #{namespace} " +
            "AND deleted = 0 " +
            "AND (config_key LIKE CONCAT('%', #{configKey}, '%') OR #{configKey} IS NULL) " +
            "AND (enabled = #{enabled} OR #{enabled} IS NULL) " +
            "ORDER BY created_at DESC")
    IPage<SysConfig> selectConfigPage(Page<SysConfig> page,
                                      @Param("namespace") String namespace,
                                      @Param("configKey") String configKey,
                                      @Param("enabled") Boolean enabled);
}
