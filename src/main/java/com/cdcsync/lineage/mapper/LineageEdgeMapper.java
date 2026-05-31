package com.cdcsync.lineage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdcsync.lineage.domain.LineageEdge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LineageEdgeMapper extends BaseMapper<LineageEdge> {

    @Select("SELECT * FROM cdc_lineage_edge WHERE (source_table = #{tableName} OR target_table = #{tableName}) AND deleted = 0")
    List<LineageEdge> selectByTableName(@Param("tableName") String tableName);

    @Select("SELECT * FROM cdc_lineage_edge WHERE target_table = #{tableName} AND target_column = #{columnName} AND deleted = 0")
    List<LineageEdge> selectUpstream(@Param("tableName") String tableName, @Param("columnName") String columnName);

    @Select("SELECT * FROM cdc_lineage_edge WHERE source_table = #{tableName} AND source_column = #{columnName} AND deleted = 0")
    List<LineageEdge> selectDownstream(@Param("tableName") String tableName, @Param("columnName") String columnName);
}
