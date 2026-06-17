package com.designsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.designsystem.entity.ComponentVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ComponentVersionMapper extends BaseMapper<ComponentVersion> {
    List<ComponentVersion> selectByComponentId(@Param("componentId") Long componentId);

    ComponentVersion selectLatestVersion(@Param("componentId") Long componentId);

    ComponentVersion selectByComponentIdAndVersion(@Param("componentId") Long componentId, @Param("version") String version);
}
