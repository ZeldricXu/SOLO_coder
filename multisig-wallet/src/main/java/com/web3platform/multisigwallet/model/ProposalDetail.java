package com.web3platform.multisigwallet.model;

import com.web3platform.persistence.model.entity.MultisigProposal;
import com.web3platform.persistence.model.entity.MultisigSignature;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProposalDetail {

    private MultisigProposal proposal;
    private List<MultisigSignature> signatures;
    private boolean canExecute;
    private int executedCount;
}
