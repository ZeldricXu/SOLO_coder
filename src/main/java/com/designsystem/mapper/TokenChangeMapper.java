package com.designsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.designsystem.entity.TokenChange;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TokenChangeMapper extends BaseMapper<TokenChange> {
    List<TokenChange> selectByTokenId(@Param("tokenId") Long tokenId);

    List<TokenChange> selectPendingMigration();

    List<TokenChange> selectByApprovalRequestId(@Param("approvalRequestId") Long approvalRequestId);
}
