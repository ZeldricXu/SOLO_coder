package com.chain.infrastructure.chainindexer.factory;

import com.chain.infrastructure.chainindexer.parser.BlockParser;
import com.chain.infrastructure.chainindexer.parser.TransactionParser;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ParserFactory {

    private final Map<String, BlockParser> blockParsers;
    private final Map<String, TransactionParser> transactionParsers;

    public ParserFactory(List<BlockParser> blockParsers, List<TransactionParser> transactionParsers) {
        this.blockParsers = blockParsers.stream()
                .collect(Collectors.toMap(p -> p.getChainType().toUpperCase(), Function.identity()));
        this.transactionParsers = transactionParsers.stream()
                .collect(Collectors.toMap(p -> p.getChainType().toUpperCase(), Function.identity()));
    }

    public BlockParser getBlockParser(String chainType) {
        BlockParser parser = blockParsers.get(chainType.toUpperCase());
        if (parser == null) {
            return blockParsers.values().iterator().next();
        }
        return parser;
    }

    public TransactionParser getTransactionParser(String chainType) {
        TransactionParser parser = transactionParsers.get(chainType.toUpperCase());
        if (parser == null) {
            return transactionParsers.values().iterator().next();
        }
        return parser;
    }
}
