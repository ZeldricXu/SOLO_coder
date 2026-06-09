package com.cardgame.netty;

import com.cardgame.common.config.NettyConfig;
import com.cardgame.netty.codec.GameMessageDecoder;
import com.cardgame.netty.codec.GameMessageEncoder;
import com.cardgame.netty.dispatcher.MessageDispatcher;
import com.cardgame.netty.handler.GameServerHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Slf4j
@Component
public class NettyGameServer {

    @Autowired
    private NettyConfig nettyConfig;

    @Autowired
    private GameServerHandler gameServerHandler;

    @Autowired
    private MessageDispatcher messageDispatcher;

    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;

    @PostConstruct
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(nettyConfig.getBossThreads());
        workerGroup = new NioEventLoopGroup(nettyConfig.getWorkerThreads());

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, nettyConfig.getSoBacklog())
                .childOption(ChannelOption.SO_KEEPALIVE, nettyConfig.isSoKeepalive())
                .childOption(ChannelOption.TCP_NODELAY, nettyConfig.isTcpNoDelay())
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new IdleStateHandler(
                                nettyConfig.getReaderIdleTimeSeconds(),
                                nettyConfig.getWriterIdleTimeSeconds(),
                                nettyConfig.getAllIdleTimeSeconds()));
                        pipeline.addLast(new GameMessageDecoder());
                        pipeline.addLast(new GameMessageEncoder());
                        pipeline.addLast(gameServerHandler);
                    }
                });

        bootstrap.bind(nettyConfig.getPort()).sync();
        log.info("Netty game server started on port: {}", nettyConfig.getPort());
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Netty game server...");
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        messageDispatcher.shutdown();
        log.info("Netty game server shutdown complete");
    }
}
