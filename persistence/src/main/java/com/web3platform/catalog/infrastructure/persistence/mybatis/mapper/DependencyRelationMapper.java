package com.web3platform.catalog.infrastructure.persistence.mybatis.mapper;

import com.web3platform.catalog.infrastructure.persistence.mybatis.entity.DependencyRelationPO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface DependencyRelationMapper {
    @Insert("INSERT INTO dependency_relation (source_id, target_id, dep_type, version_constraint) " +
            "VALUES (#{sourceId}, #{targetId}, #{depType}, #{versionConstraint})")
    void insert(DependencyRelationPO po);

    @Delete("DELETE FROM dependency_relation WHERE source_id = #{sourceId} AND target_id = #{targetId}")
    void delete(@Param("sourceId") String sourceId, @Param("targetId") String targetId);

    @Select("SELECT * FROM dependency_relation WHERE source_id = #{serviceId}")
    @Results(id = "DependencyResultMap", value = {
        @Result(property = "sourceId", column = "source_id"),
        @Result(property = "targetId", column = "target_id"),
        @Result(property = "depType", column = "dep_type"),
        @Result(property = "versionConstraint", column = "version_constraint")
    })
    List<DependencyRelationPO> findDependenciesOf(String serviceId);

    @Select("SELECT * FROM dependency_relation WHERE target_id = #{serviceId}")
    @ResultMap("DependencyResultMap")
    List<DependencyRelationPO> findDependentsOf(String serviceId);

    @Delete("DELETE FROM dependency_relation WHERE source_id = #{serviceId} OR target_id = #{serviceId}")
    void deleteAllForService(String serviceId);
}
