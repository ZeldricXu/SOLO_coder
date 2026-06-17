package com.designsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.designsystem.entity.Project;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProjectMapper extends BaseMapper<Project> {
    List<Project> selectSubscribedProjects();
}
