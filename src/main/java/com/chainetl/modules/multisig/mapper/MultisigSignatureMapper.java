package com.chainetl.modules.multisig.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chainetl.modules.multisig.model.MultisigSignature;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MultisigSignatureMapper extends BaseMapper<MultisigSignature> {
}
