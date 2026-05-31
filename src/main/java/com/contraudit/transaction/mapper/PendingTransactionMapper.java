package com.contraudit.transaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.contraudit.transaction.entity.PendingTransaction;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PendingTransactionMapper extends BaseMapper<PendingTransaction> {
}
