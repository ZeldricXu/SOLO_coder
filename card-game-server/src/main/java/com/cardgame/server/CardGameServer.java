package com.cardgame.server;

import com.cardgame.netty.NettyGameServer;
import com.cardgame.rank.service.DailyChallengeService;
import com.cardgame.rank.service.SeasonService;
import com.cardgame.room.manager.MatchmakingManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CardGameServer {

    private static AnnotationConfigApplicationContext context;
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public static void main(String[] args) {
        try {
            log.info("Starting Card Game Server...");

            context = new AnnotationConfigApplicationContext(com.cardgame.server.config.SpringConfig.class);
            context.registerShutdownHook();

            startNettyServer();
            startScheduledTasks();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down server...");
                shutdown();
            }));

            log.info("Card Game Server started successfully!");

            Thread.currentThread().join();
        } catch (Exception e) {
            log.error("Server startup failed", e);
            System.exit(1);
        }
    }

    private static void startNettyServer() {
        NettyGameServer nettyServer = context.getBean(NettyGameServer.class);
        new Thread(() -> {
            try {
                nettyServer.start();
            } catch (Exception e) {
                log.error("Netty server failed", e);
            }
        }, "netty-server").start();
    }

    private static void startScheduledTasks() {
        MatchmakingManager matchmakingManager = context.getBean(MatchmakingManager.class);
        scheduler.scheduleAtFixedRate(
                matchmakingManager::processMatchmakingQueue,
                0, 5, TimeUnit.SECONDS
        );
        log.info("Matchmaking scheduler started (every 5 seconds)");

        DailyChallengeService dailyChallengeService = context.getBean(DailyChallengeService.class);
        scheduler.scheduleAtFixedRate(
                dailyChallengeService::generateTodayChallenge,
                0, 1, TimeUnit.HOURS
        );
        log.info("Daily challenge scheduler started (every hour)");

        SeasonService seasonService = context.getBean(SeasonService.class);
        scheduler.scheduleAtFixedRate(
                seasonService::checkSeasonEnd,
                0, 1, TimeUnit.HOURS
        );
        log.info("Season check scheduler started (every hour)");
    }

    private static void shutdown() {
        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }

            if (context != null) {
                context.close();
            }

            log.info("Server shutdown complete");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }
}
