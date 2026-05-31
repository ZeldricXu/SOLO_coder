package com.dynamiclog.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dynamiclog.common.entity.Config;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Optional;

@Mapper
public interface ConfigMapper extends BaseMapper<Config> {

    @Select("SELECT * FROM config WHERE data_id = #{dataId} AND namespace = #{namespace} AND deleted = 0 ORDER BY version DESC LIMIT 1")
    Optional<Config> findLatestByDataIdAndNamespace(@Param("dataId") String dataId, @Param("namespace") String namespace);

    @Select("SELECT * FROM config WHERE data_id = #{dataId} AND namespace = #{namespace} AND version = #{version} AND deleted = 0")
    Optional<Config> findByDataIdAndNamespaceAndVersion(@Param("dataId") String dataId, @Param("namespace") String namespace, @Param("version") Integer version);

    @Select("SELECT * FROM config WHERE namespace = #{namespace} AND deleted = 0 ORDER BY created_at DESC")
    List<Config> findByNamespace(@Param("namespace") String namespace);

    @Select("SELECT MAX(version) FROM config WHERE data_id = #{dataId} AND namespace = #{namespace} AND deleted = 0")
    Integer getMaxVersion(@Param("dataId") String dataId, @Param("namespace") String namespace);
}
