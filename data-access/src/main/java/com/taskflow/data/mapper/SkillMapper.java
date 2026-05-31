package com.taskflow.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.data.entity.SkillEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SkillMapper extends BaseMapper<SkillEntity> {

    @Select("SELECT * FROM skill WHERE tenant_id = #{tenantId} AND parent_id IS NULL ORDER BY sort_order")
    List<SkillEntity> selectRootSkills(@Param("tenantId") String tenantId);

    @Select("SELECT * FROM skill WHERE tenant_id = #{tenantId} AND parent_id = #{parentId} ORDER BY sort_order")
    List<SkillEntity> selectByParentId(@Param("tenantId") String tenantId, @Param("parentId") String parentId);

    @Select("SELECT * FROM skill WHERE tenant_id = #{tenantId} AND category = #{category} ORDER BY level, sort_order")
    List<SkillEntity> selectByCategory(@Param("tenantId") String tenantId, @Param("category") String category);
}
