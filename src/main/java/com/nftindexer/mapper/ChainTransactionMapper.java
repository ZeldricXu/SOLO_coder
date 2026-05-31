package com.nftindexer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nftindexer.entity.ChainTransaction;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChainTransactionMapper extends BaseMapper<ChainTransaction> {
}
