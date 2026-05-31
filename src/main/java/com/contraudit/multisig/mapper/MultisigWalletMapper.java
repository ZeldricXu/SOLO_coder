package com.contraudit.multisig.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.contraudit.multisig.entity.MultisigWallet;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MultisigWalletMapper extends BaseMapper<MultisigWallet> {
}
