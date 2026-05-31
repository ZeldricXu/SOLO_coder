package com.web3platform.chainindexer.model;

import com.web3platform.persistence.model.entity.ChainBlock;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexedBlock {

    private ChainBlock chainBlock;
    private List<IndexedTransaction> transactions;
}
