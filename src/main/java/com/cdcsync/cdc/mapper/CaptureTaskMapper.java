package com.cdcsync.cdc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdcsync.cdc.domain.CaptureTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CaptureTaskMapper extends BaseMapper<CaptureTask> {
}
