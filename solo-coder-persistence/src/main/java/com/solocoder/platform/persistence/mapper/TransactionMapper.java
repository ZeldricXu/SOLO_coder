package com.solocoder.platform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solocoder.platform.persistence.entity.TransactionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TransactionMapper extends BaseMapper<TransactionEntity> {
}
