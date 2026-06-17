package com.designsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.designsystem.entity.ComponentProp;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ComponentPropMapper extends BaseMapper<ComponentProp> {
    List<ComponentProp> selectByVersionId(@Param("versionId") Long versionId);

    int deleteByVersionId(@Param("versionId") Long versionId);
}
