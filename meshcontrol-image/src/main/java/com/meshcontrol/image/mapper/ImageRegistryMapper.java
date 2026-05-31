package com.meshcontrol.image.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.image.entity.ImageRegistry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ImageRegistryMapper extends BaseMapper<ImageRegistry> {

    @Select("SELECT * FROM image_registry WHERE enabled = 1 AND deleted = 0 ORDER BY priority ASC")
    List<ImageRegistry> findAllEnabled();
}
