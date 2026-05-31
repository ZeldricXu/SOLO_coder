package com.contraudit.multisig.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.contraudit.multisig.entity.MultisigSigner;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MultisigSignerMapper extends BaseMapper<MultisigSigner> {
}
