package com.supplychain.history.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.supplychain.common.entity.HistoryRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HistoryRecordMapper extends BaseMapper<HistoryRecord> {}
