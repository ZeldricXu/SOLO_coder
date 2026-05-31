package com.contractai.metering.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.metering.entity.UsageRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UsageRecordMapper extends BaseMapper<UsageRecord> {
}
