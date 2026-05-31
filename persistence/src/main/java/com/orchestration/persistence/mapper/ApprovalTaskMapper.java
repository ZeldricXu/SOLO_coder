package com.orchestration.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orchestration.persistence.entity.ApprovalTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApprovalTaskMapper extends BaseMapper<ApprovalTask> {
}
