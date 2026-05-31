package com.contractai.sla.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.sla.entity.SlaRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SlaRecordMapper extends BaseMapper<SlaRecord> {

    @Select("SELECT * FROM sla_record WHERE tenant_id = #{tenantId} AND status IN ('pending', 'in_progress') " +
            "AND (response_deadline <= #{now} OR resolution_deadline <= #{now}) AND deleted = 0")
    List<SlaRecord> findBreachedRecords(@Param("tenantId") Long tenantId, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM sla_record WHERE tenant_id = #{tenantId} AND status IN ('pending', 'in_progress') " +
            "AND deleted = 0 FOR UPDATE")
    List<SlaRecord> findAllActiveRecordsForUpdate(@Param("tenantId") Long tenantId);
}
