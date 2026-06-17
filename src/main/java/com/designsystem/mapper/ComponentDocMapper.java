package com.designsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.designsystem.entity.ComponentDoc;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ComponentDocMapper extends BaseMapper<ComponentDoc> {
    List<ComponentDoc> selectByVersionId(@Param("versionId") Long versionId);

    List<ComponentDoc> selectUnindexedDocs();
}
