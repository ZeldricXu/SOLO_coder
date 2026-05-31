package com.chain.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chain.infrastructure.persistence.entity.CrossChainTransfer;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CrossChainTransferMapper extends BaseMapper<CrossChainTransfer> {
}
