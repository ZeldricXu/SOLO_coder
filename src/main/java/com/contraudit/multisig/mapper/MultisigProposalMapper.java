package com.contraudit.multisig.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.contraudit.multisig.entity.MultisigProposal;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MultisigProposalMapper extends BaseMapper<MultisigProposal> {
}
