package com.cardgame.common.config;

import lombok.Data;

@Data
public class NettyConfig {
    private int port = 8888;
    private int bossThreads = 1;
    private int workerThreads = 8;
    private int soBacklog = 1024;
    private boolean soKeepalive = true;
    private boolean tcpNoDelay = true;
    private int readerIdleTimeSeconds = 60;
    private int writerIdleTimeSeconds = 0;
    private int allIdleTimeSeconds = 0;
}
