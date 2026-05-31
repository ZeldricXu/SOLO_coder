package com.web3platform.storageadapter.util;

import com.web3platform.storageadapter.constant.StorageConstants;
import org.springframework.lang.NonNull;

import java.util.concurrent.ThreadLocalRandom;

public final class StreamBufferPool {

    private static final ThreadLocal<byte[]> BUFFER_CACHE = ThreadLocal.withInitial(
            () -> new byte[StorageConstants.DEFAULT_STREAM_BUFFER_SIZE]);

    private StreamBufferPool() {}

    @NonNull
    public static byte[] borrowBuffer() {
        return BUFFER_CACHE.get();
    }

    public static void releaseBuffer() {
    }

    public static void clear() {
        BUFFER_CACHE.remove();
    }
}
