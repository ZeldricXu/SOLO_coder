package com.meshcontrol.image.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.image.entity.ImageSyncTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ImageSyncTaskMapper extends BaseMapper<ImageSyncTask> {

    @Select("SELECT * FROM image_sync_task WHERE status = #{status}")
    List<ImageSyncTask> findByStatus(@Param("status") String status);

    @Update("UPDATE image_sync_task SET progress = #{progress}, synced_images = #{syncedImages} WHERE task_id = #{taskId}")
    int updateProgress(@Param("taskId") String taskId,
                       @Param("progress") Double progress,
                       @Param("syncedImages") Integer syncedImages);
}
