package com.contractai.approval.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.approval.entity.ApprovalStage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ApprovalStageMapper extends BaseMapper<ApprovalStage> {

    @Select("SELECT * FROM approval_stage WHERE process_id = #{processId} AND tenant_id = #{tenantId} AND deleted = 0 ORDER BY stage_index")
    List<ApprovalStage> findByProcessId(@Param("processId") Long processId, @Param("tenantId") Long tenantId);
}
