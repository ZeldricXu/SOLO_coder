package com.edgescheduler.ota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.ota.entity.OtaDeviceUpgrade;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OtaDeviceUpgradeMapper extends BaseMapper<OtaDeviceUpgrade> {

    @Select("SELECT * FROM ota_device_upgrade WHERE job_id = #{jobId} AND device_key = #{deviceKey}")
    OtaDeviceUpgrade selectByJobIdAndDeviceKey(@Param("jobId") String jobId,
                                                @Param("deviceKey") String deviceKey);

    @Select("SELECT * FROM ota_device_upgrade WHERE job_id = #{jobId}")
    List<OtaDeviceUpgrade> selectByJobId(@Param("jobId") String jobId);

    @Select("SELECT * FROM ota_device_upgrade WHERE job_id = #{jobId} AND batch_number = #{batchNumber}")
    List<OtaDeviceUpgrade> selectByJobIdAndBatch(@Param("jobId") String jobId,
                                                  @Param("batchNumber") Integer batchNumber);

    @Select("SELECT COUNT(*) FROM ota_device_upgrade WHERE job_id = #{jobId} AND status = #{status}")
    int countByJobIdAndStatus(@Param("jobId") String jobId,
                               @Param("status") String status);

    @Update("UPDATE ota_device_upgrade SET status = #{status}, progress = #{progress}, " +
            "error_code = #{errorCode}, error_message = #{errorMessage}, retry_count = #{retryCount}, " +
            "updated_at = NOW() WHERE id = #{id}")
    int updateUpgradeStatus(@Param("id") Long id,
                            @Param("status") String status,
                            @Param("progress") Integer progress,
                            @Param("errorCode") String errorCode,
                            @Param("errorMessage") String errorMessage,
                            @Param("retryCount") Integer retryCount);

    @Update("UPDATE ota_device_upgrade SET status = #{status}, completed_at = #{completedAt}, " +
            "progress = 100, updated_at = NOW() WHERE id = #{id}")
    int completeUpgrade(@Param("id") Long id,
                        @Param("status") String status,
                        @Param("completedAt") LocalDateTime completedAt);
}
