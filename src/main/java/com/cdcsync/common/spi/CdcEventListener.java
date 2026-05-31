package com.cdcsync.common.spi;

import com.cdcsync.cdc.domain.ChangeEvent;

public interface CdcEventListener {

    void onEvent(ChangeEvent event);

    void onError(Throwable throwable);

    void onCompleted();
}
