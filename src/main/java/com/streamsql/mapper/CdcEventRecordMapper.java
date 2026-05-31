package com.streamsql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.streamsql.entity.CdcEventRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CdcEventRecordMapper extends BaseMapper<CdcEventRecord> {
}
