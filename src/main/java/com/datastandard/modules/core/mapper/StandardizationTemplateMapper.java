package com.datastandard.modules.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datastandard.modules.core.entity.StandardizationTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

@Mapper
public interface StandardizationTemplateMapper extends BaseMapper<StandardizationTemplate> {

    @Select("SELECT * FROM standardization_templates WHERE data_source = #{dataSource} " +
            "AND dataset_name = #{datasetName} AND active = 1 AND deleted = 0 " +
            "ORDER BY version DESC LIMIT 1")
    Optional<StandardizationTemplate> findActiveByDataSourceAndDataset(
            @Param("dataSource") String dataSource,
            @Param("datasetName") String datasetName);

    @Select("SELECT * FROM standardization_templates WHERE template_id = #{templateId} AND deleted = 0")
    Optional<StandardizationTemplate> findById(@Param("templateId") String templateId);

    @Select("SELECT * FROM standardization_templates WHERE data_source = #{dataSource} AND deleted = 0")
    List<StandardizationTemplate> findByDataSource(@Param("dataSource") String dataSource);
}
