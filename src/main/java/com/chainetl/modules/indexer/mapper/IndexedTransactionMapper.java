package com.chainetl.modules.indexer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chainetl.modules.indexer.model.IndexedTransaction;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IndexedTransactionMapper extends BaseMapper<IndexedTransaction> {
}
