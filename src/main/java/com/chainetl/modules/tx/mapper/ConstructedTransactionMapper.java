package com.chainetl.modules.tx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chainetl.modules.tx.model.ConstructedTransaction;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConstructedTransactionMapper extends BaseMapper<ConstructedTransaction> {
}
