package com.battle.platform.matching;

import com.battle.platform.battlefield.BattlefieldManager;
import com.battle.platform.config.MatchingProperties;
import com.battle.platform.entity.ServerStat;
import com.battle.platform.netty.GameServerHandler;
import com.battle.platform.protocol.GameMessage;
import com.battle.platform.repository.PlayerRepository;
import com.battle.platform.repository.ServerStatRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingEngine {

    private final MatchingProperties matchingProperties;
    private final BattlefieldManager battlefieldManager;
    private final ServerStatRepository serverStatRepository;
    private final PlayerRepository playerRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<Long, MatchingPlayer> waitingPlayers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, PriorityQueue<MatchingTicket>> bracketQueues = new ConcurrentHashMap<>();

    private static final String MATCH_CHANNEL = "battle:match:channel";
    private static final int PLAYERS_PER_MATCH = 200;

    public void joinMatch(Long playerId) {
        var playerOpt = playerRepository.findByPlayerId(playerId);
        if (playerOpt.isEmpty()) {
            log.warn("Player {} not found, cannot join match", playerId);
            return;
        }

        var player = playerOpt.get();
        if (player.getIsBanned()) {
            log.warn("Player {} is banned, cannot join match", playerId);
            return;
        }

        var serverStatOpt = serverStatRepository.findByServerId(player.getServerId());
        double serverPower = serverStatOpt.map(ServerStat::getServerPowerScore).orElse(0.0);

        MatchingPlayer mp = MatchingPlayer.builder()
                .playerId(playerId)
                .serverId(player.getServerId())
                .combatPower(player.getCombatPower())
                .rating(player.getRating())
                .serverPowerScore(serverPower)
                .joinTimeMs(System.currentTimeMillis())
                .guildId(player.getGuildId())
                .build();

        int bracket = mp.getRatingBracket(matchingProperties.getRatingBracketSize());
        double priority = mp.getCompositeRating() + mp.getWaitTimePriority(matchingProperties.getWaitTimeWeight());

        MatchingTicket ticket = new MatchingTicket(mp, bracket, priority);
        waitingPlayers.put(playerId, mp);

        bracketQueues.computeIfAbsent(bracket, k -> new PriorityQueue<>()).add(ticket);

        log.info("Player {} (server={}, rating={}, bracket={}) joined match queue",
                playerId, player.getServerId(), String.format("%.1f", mp.getCompositeRating()), bracket);
    }

    public void leaveMatch(Long playerId) {
        MatchingPlayer removed = waitingPlayers.remove(playerId);
        if (removed != null) {
            log.info("Player {} left match queue", playerId);
        }
    }

    @Scheduled(fixedDelayString = "${battle.matching.tick-interval-ms:500}")
    public void tick() {
        if (waitingPlayers.isEmpty()) {
            return;
        }

        expandBrackets();

        for (Map.Entry<Integer, PriorityQueue<MatchingTicket>> entry : bracketQueues.entrySet()) {
            PriorityQueue<MatchingTicket> queue = entry.getValue();
            List<MatchingTicket> matched = new ArrayList<>();

            while (!queue.isEmpty() && matched.size() < PLAYERS_PER_MATCH) {
                MatchingTicket ticket = queue.poll();
                if (ticket == null) break;

                MatchingPlayer mp = waitingPlayers.get(ticket.getPlayer().getPlayerId());
                if (mp == null) continue;

                ticket.setPriority(mp.getCompositeRating() + mp.getWaitTimePriority(matchingProperties.getWaitTimeWeight()));
                matched.add(ticket);
            }

            if (matched.size() >= PLAYERS_PER_MATCH) {
                createBattle(matched.subList(0, PLAYERS_PER_MATCH));
                for (int i = PLAYERS_PER_MATCH; i < matched.size(); i++) {
                    queue.add(matched.get(i));
                }
            } else {
                for (MatchingTicket t : matched) {
                    queue.add(t);
                }
            }
        }

        cleanupEmptyBrackets();
    }

    private void expandBrackets() {
        long now = System.currentTimeMillis();
        for (MatchingPlayer mp : waitingPlayers.values()) {
            long waitTime = now - mp.getJoinTimeMs();
            if (waitTime > matchingProperties.getMaxWaitTimeMs() / 2) {
                int currentBracket = mp.getRatingBracket(matchingProperties.getRatingBracketSize());
                int expandedBracket = mp.getRatingBracket(matchingProperties.getRatingBracketSize() * 2);
                if (currentBracket != expandedBracket) {
                    double priority = mp.getCompositeRating() + mp.getWaitTimePriority(matchingProperties.getWaitTimeWeight());
                    MatchingTicket ticket = new MatchingTicket(mp, expandedBracket, priority);
                    bracketQueues.computeIfAbsent(expandedBracket, k -> new PriorityQueue<>()).add(ticket);
                }
            }
        }
    }

    private void createBattle(List<MatchingTicket> tickets) {
        List<Long> playerIds = tickets.stream()
                .map(t -> t.getPlayer().getPlayerId())
                .toList();

        for (MatchingTicket t : tickets) {
            waitingPlayers.remove(t.getPlayer().getPlayerId());
        }

        String battleId = battlefieldManager.createBattlefield(playerIds);

        for (Long pid : playerIds) {
            GameMessage msg = GameMessage.builder()
                    .msgId(GameMessage.MSG_MATCH_RESULT)
                    .msgType(GameMessage.TYPE_PUSH)
                    .playerId(pid)
                    .timestamp(System.currentTimeMillis())
                    .payload(battleId.getBytes())
                    .build();

            Channel ch = GameServerHandler.getPlayerChannel(pid);
            if (ch != null && ch.isActive()) {
                ch.writeAndFlush(msg);
            }
        }

        log.info("Created battle {} with {} players", battleId, playerIds.size());
    }

    private void cleanupEmptyBrackets() {
        bracketQueues.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    public int getWaitingCount() {
        return waitingPlayers.size();
    }

    public Map<Integer, Integer> getBracketStats() {
        Map<Integer, Integer> stats = new HashMap<>();
        for (Map.Entry<Integer, PriorityQueue<MatchingTicket>> e : bracketQueues.entrySet()) {
            stats.put(e.getKey(), e.getValue().size());
        }
        return stats;
    }
}
