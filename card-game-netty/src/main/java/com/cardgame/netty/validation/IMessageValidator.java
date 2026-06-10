package com.cardgame.netty.validation;

import com.cardgame.common.protocol.GameMessage;

public interface IMessageValidator {
    ValidationResult validate(GameMessage message);

    default String getName() {
        return this.getClass().getSimpleName();
    }

    default int getOrder() {
        return 0;
    }
}
