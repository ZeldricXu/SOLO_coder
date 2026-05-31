package com.contractai.approval.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.approval.entity.ApprovalTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ApprovalTaskMapper extends BaseMapper<ApprovalTask> {

    @Select("SELECT * FROM approval_task WHERE process_id = #{processId} AND tenant_id = #{tenantId} AND deleted = 0 ORDER BY id")
    List<ApprovalTask> findByProcessId(@Param("processId") Long processId, @Param("tenantId") Long tenantId);

    @Select("SELECT * FROM approval_task WHERE stage_id = #{stageId} AND tenant_id = #{tenantId} AND deleted = 0 ORDER BY id")
    List<ApprovalTask> findByStageId(@Param("stageId") Long stageId, @Param("tenantId") Long tenantId);
}
