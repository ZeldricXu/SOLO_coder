package com.edgescheduler.ota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.ota.entity.OtaJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface OtaJobMapper extends BaseMapper<OtaJob> {

    @Select("SELECT * FROM ota_job WHERE job_id = #{jobId} AND deleted = 0")
    OtaJob selectByJobId(@Param("jobId") String jobId);

    @Select("SELECT * FROM ota_job WHERE firmware_id = #{firmwareId} AND deleted = 0")
    List<OtaJob> selectByFirmwareId(@Param("firmwareId") String firmwareId);

    @Select("SELECT * FROM ota_job WHERE status = #{status} AND deleted = 0 ORDER BY created_at ASC")
    List<OtaJob> selectByStatus(@Param("status") String status);

    @Update("UPDATE ota_job SET current_batch = #{currentBatch}, status = #{status}, updated_at = NOW() " +
            "WHERE job_id = #{jobId} AND deleted = 0")
    int updateBatchAndStatus(@Param("jobId") String jobId,
                              @Param("currentBatch") Integer currentBatch,
                              @Param("status") String status);
}
