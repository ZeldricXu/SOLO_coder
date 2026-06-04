package com.battle.platform.netty;

import com.battle.platform.protocol.GameMessageDecoder;
import com.battle.platform.protocol.GameMessageEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameNettyServer {

    @Value("${netty.server.port:9090}")
    private int configuredPort;

    @Value("${netty.server.dynamic-port.enabled:false}")
    private boolean dynamicPortEnabled;

    @Value("${netty.server.dynamic-port.range-start:10000}")
    private int dynamicPortRangeStart;

    @Value("${netty.server.dynamic-port.range-end:19999}")
    private int dynamicPortRangeEnd;

    @Value("${netty.server.dynamic-port.max-retry:5}")
    private int maxRetry;

    @Value("${netty.server.boss-threads:2}")
    private int bossThreads;

    @Value("${netty.server.so-backlog:1024}")
    private int soBacklog;

    @Value("${netty.server.so-keepalive:true}")
    private boolean soKeepalive;

    @Value("${netty.server.tcp-nodelay:true}")
    private boolean tcpNodelay;

    private final GameServerHandler gameServerHandler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    @Getter
    private int actualPort;

    @Getter
    private volatile boolean started = false;

    @PostConstruct
    public void start() {
        bossGroup = new NioEventLoopGroup(bossThreads);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, soBacklog)
                .childOption(ChannelOption.SO_KEEPALIVE, soKeepalive)
                .childOption(ChannelOption.TCP_NODELAY, tcpNodelay)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast("idleState", new IdleStateHandler(300, 0, 0));
                        pipeline.addLast("decoder", new GameMessageDecoder());
                        pipeline.addLast("encoder", new GameMessageEncoder());
                        pipeline.addLast("handler", gameServerHandler);
                    }
                });

        int portToBind = configuredPort;
        int retry = 0;

        while (retry < maxRetry) {
            try {
                if (dynamicPortEnabled) {
                    portToBind = dynamicPortRangeStart +
                            (int) (Math.random() * (dynamicPortRangeEnd - dynamicPortRangeStart + 1));
                }

                ChannelFuture future = bootstrap.bind(portToBind).sync();

                if (future.isSuccess()) {
                    serverChannel = future.channel();
                    actualPort = portToBind;
                    started = true;
                    registerToServiceDiscovery(actualPort);
                    log.info("Netty server started successfully on port {}", actualPort);
                    return;
                }

            } catch (Exception e) {
                log.warn("Failed to bind port {} (attempt {}/{}): {}",
                        portToBind, retry + 1, maxRetry, e.getMessage());
            }

            if (!dynamicPortEnabled) {
                log.error("Fixed port {} binding failed, aborting", configuredPort);
                break;
            }

            retry++;
        }

        if (!started) {
            log.error("Failed to start Netty server after {} attempts", maxRetry);
            throw new RuntimeException("Netty server failed to start after " + maxRetry + " attempts");
        }
    }

    private void registerToServiceDiscovery(int port) {
        log.info("Registering server with port {} to service discovery", port);
    }

    @PreDestroy
    public void shutdown() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        started = false;
        log.info("Netty server shut down, port {} released", actualPort);
    }
}
