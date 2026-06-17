package com.designsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.designsystem.entity.ApprovalRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApprovalRequestMapper extends BaseMapper<ApprovalRequest> {
    IPage<ApprovalRequest> selectApprovalPage(Page<ApprovalRequest> page, @Param("status") String status, @Param("requestType") String requestType, @Param("approverId") Long approverId);

    List<ApprovalRequest> selectPendingByApproverId(@Param("approverId") Long approverId);

    ApprovalRequest selectByTarget(@Param("targetType") String targetType, @Param("targetId") Long targetId, @Param("status") String status);
}
