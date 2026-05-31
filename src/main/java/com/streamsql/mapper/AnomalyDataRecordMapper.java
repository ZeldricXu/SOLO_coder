package com.streamsql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.streamsql.entity.AnomalyDataRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnomalyDataRecordMapper extends BaseMapper<AnomalyDataRecord> {
}
