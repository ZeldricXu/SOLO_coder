package com.nftindexer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nftindexer.entity.TransactionRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TransactionRecordMapper extends BaseMapper<TransactionRecord> {
}
