package com.datastandard.modules.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datastandard.common.model.ConfigDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ConfigDefinitionMapper extends BaseMapper<ConfigDefinition> {

    @Select("SELECT * FROM config_definition WHERE config_key = #{configKey} AND scope = #{scope} AND is_enabled = 1")
    ConfigDefinition findByKeyAndScope(@Param("configKey") String configKey, @Param("scope") String scope);

    @Select("SELECT * FROM config_definition WHERE config_key = #{configKey} ORDER BY version DESC LIMIT 1")
    ConfigDefinition findLatestByKey(@Param("configKey") String configKey);

    @Select("SELECT * FROM config_definition WHERE scope = #{scope} AND is_enabled = 1")
    List<ConfigDefinition> findByScope(@Param("scope") String scope);

    @Select("SELECT * FROM config_definition WHERE config_key = #{configKey} AND version = #{version}")
    ConfigDefinition findByKeyAndVersion(@Param("configKey") String configKey, @Param("version") Integer version);

    @Select("SELECT MAX(version) FROM config_definition WHERE config_key = #{configKey}")
    Integer getMaxVersion(@Param("configKey") String configKey);

    @Select("SELECT * FROM config_definition WHERE updated_at BETWEEN #{startTime} AND #{endTime}")
    List<ConfigDefinition> findUpdatedBetween(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    @Select("SELECT * FROM config_definition WHERE is_enabled = 1")
    List<ConfigDefinition> findAllEnabled();
}
