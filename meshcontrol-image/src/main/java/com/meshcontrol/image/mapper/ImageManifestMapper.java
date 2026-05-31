package com.meshcontrol.image.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.image.entity.ImageManifest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ImageManifestMapper extends BaseMapper<ImageManifest> {

    @Select("SELECT * FROM image_manifest WHERE repo_id = #{repoId} AND deleted = 0 ORDER BY created_at DESC")
    List<ImageManifest> findByRepoId(@Param("repoId") String repoId);

    @Select("SELECT * FROM image_manifest WHERE repo_id = #{repoId} AND tag = #{tag} AND deleted = 0 LIMIT 1")
    ImageManifest findByRepoIdAndTag(@Param("repoId") String repoId, @Param("tag") String tag);

    @Update("UPDATE image_manifest SET pull_count = pull_count + 1 WHERE id = #{id}")
    int incrementPullCount(@Param("id") Long id);

    @Select("SELECT * FROM image_manifest WHERE p2p_enabled = 1 AND deleted = 0")
    List<ImageManifest> findP2pEnabled();
}
