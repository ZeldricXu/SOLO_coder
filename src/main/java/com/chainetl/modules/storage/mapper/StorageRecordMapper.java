package com.chainetl.modules.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chainetl.modules.storage.model.StorageRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StorageRecordMapper extends BaseMapper<StorageRecord> {
}
