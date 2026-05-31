package com.meshcontrol.image.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.image.entity.ImageRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ImageRepositoryMapper extends BaseMapper<ImageRepository> {

    @Select("SELECT * FROM image_repository WHERE registry_id = #{registryId} AND deleted = 0")
    List<ImageRepository> findByRegistryId(@Param("registryId") String registryId);

    @Select("SELECT * FROM image_repository WHERE sync_enabled = 1 AND deleted = 0")
    List<ImageRepository> findSyncEnabled();
}
