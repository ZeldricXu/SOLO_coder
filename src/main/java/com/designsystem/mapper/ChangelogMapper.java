package com.designsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.designsystem.entity.Changelog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChangelogMapper extends BaseMapper<Changelog> {
    List<Changelog> selectByComponentId(@Param("componentId") Long componentId);

    List<Changelog> selectUnreleasedByComponentId(@Param("componentId") Long componentId);

    List<Changelog> selectByComponentIdAndVersion(@Param("componentId") Long componentId, @Param("version") String version);
}
