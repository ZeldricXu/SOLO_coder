package com.contraudit.transaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.contraudit.transaction.entity.SigningPolicy;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SigningPolicyMapper extends BaseMapper<SigningPolicy> {
}
