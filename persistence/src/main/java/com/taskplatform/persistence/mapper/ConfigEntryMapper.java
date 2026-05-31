package com.taskplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskplatform.persistence.entity.ConfigEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface ConfigEntryMapper extends BaseMapper<ConfigEntry> {

    @Select("SELECT * FROM config_entries WHERE namespace = #{namespace} AND enabled = 1")
    List<ConfigEntry> findByNamespace(@Param("namespace") String namespace);

    @Select("SELECT * FROM config_entries WHERE namespace = #{namespace} AND config_key = #{key} ORDER BY version DESC LIMIT 1")
    ConfigEntry findLatestByNamespaceAndKey(@Param("namespace") String namespace, @Param("key") String key);
}
