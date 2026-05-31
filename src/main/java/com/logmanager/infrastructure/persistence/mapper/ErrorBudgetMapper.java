package com.logmanager.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.logmanager.infrastructure.persistence.entity.ErrorBudgetPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ErrorBudgetMapper extends BaseMapper<ErrorBudgetPO> {
}
