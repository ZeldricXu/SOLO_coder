package com.web3platform.chaininteraction.event;

import com.web3platform.chaininteraction.model.UnifiedBlock;
import org.springframework.context.ApplicationEvent;

public class NewBlockEvent extends ApplicationEvent {

    private final String chainId;
    private final UnifiedBlock block;

    public NewBlockEvent(Object source, String chainId, UnifiedBlock block) {
        super(source);
        this.chainId = chainId;
        this.block = block;
    }

    public String getChainId() {
        return chainId;
    }

    public UnifiedBlock getBlock() {
        return block;
    }
}
