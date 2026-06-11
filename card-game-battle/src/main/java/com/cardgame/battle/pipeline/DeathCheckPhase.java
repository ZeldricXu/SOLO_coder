package com.cardgame.battle.pipeline;

import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.BattleStatus;
import com.cardgame.common.service.BattleLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class DeathCheckPhase extends BattlePhase {

    private final BattleLogService battleLogService;
    private final Runnable cleanupCallback;

    @Override
    public void execute(BattleContext context) throws Exception {
        log.debug("Executing DeathCheckPhase for battle {}", context.getBattleId());

        checkPlayerDeaths(context);
        checkEnemyDeaths(context);

        if (checkBattleEnd(context)) {
            return;
        }

        fireNext(context);
    }

    private void checkPlayerDeaths(BattleContext context) {
        List<Player> deadPlayers = new ArrayList<>();
        Iterator<Player> iterator = context.getPlayers().iterator();
        
        while (iterator.hasNext()) {
            Player player = iterator.next();
            if (!player.isAlive() && player.getCurrentHp() <= 0) {
                log.debug("Player {} has died in battle {}", player.getName(), context.getBattleId());
                deadPlayers.add(player);
            }
        }

        for (Player deadPlayer : deadPlayers) {
            context.getCharacterMap().remove(deadPlayer.getPlayerId());
            if (battleLogService != null) {
                try {
                    var action = com.cardgame.common.entity.BattleAction.builder()
                            .actionId(java.util.UUID.randomUUID().toString())
                            .actorId(deadPlayer.getPlayerId())
                            .isPlayerAction(true)
                            .actionType("DEATH")
                            .description(deadPlayer.getName() + " has been defeated")
                            .timestamp(System.currentTimeMillis())
                            .build();
                    battleLogService.logAction(context.getBattleId(), action);
                } catch (Exception e) {
                    log.debug("Failed to log player death", e);
                }
            }
        }
    }

    private void checkEnemyDeaths(BattleContext context) {
        List<Enemy> deadEnemies = new ArrayList<>();
        Iterator<Enemy> iterator = context.getEnemies().iterator();
        
        while (iterator.hasNext()) {
            Enemy enemy = iterator.next();
            if (!enemy.isAlive() && enemy.getCurrentHp() <= 0) {
                log.debug("Enemy {} has died in battle {}", enemy.getName(), context.getBattleId());
                deadEnemies.add(enemy);
                iterator.remove();
            }
        }

        for (Enemy deadEnemy : deadEnemies) {
            context.getCharacterMap().remove(deadEnemy.getId());
            if (battleLogService != null) {
                try {
                    var action = com.cardgame.common.entity.BattleAction.builder()
                            .actionId(java.util.UUID.randomUUID().toString())
                            .actorId(deadEnemy.getId())
                            .isPlayerAction(false)
                            .actionType("DEATH")
                            .description(deadEnemy.getName() + " has been defeated")
                            .timestamp(System.currentTimeMillis())
                            .build();
                    battleLogService.logAction(context.getBattleId(), action);
                } catch (Exception e) {
                    log.debug("Failed to log enemy death", e);
                }
            }
        }
    }

    private boolean checkBattleEnd(BattleContext context) {
        if (context.checkVictory()) {
            context.setStatus(BattleStatus.VICTORY);
            context.setEndTime(System.currentTimeMillis());
            log.info("Battle {} ended in victory", context.getBattleId());
            finalizeBattle(context);
            return true;
        }

        if (context.checkDefeat()) {
            context.setStatus(BattleStatus.DEFEAT);
            context.setEndTime(System.currentTimeMillis());
            log.info("Battle {} ended in defeat", context.getBattleId());
            finalizeBattle(context);
            return true;
        }

        return false;
    }

    private void finalizeBattle(BattleContext context) {
        if (battleLogService != null) {
            try {
                battleLogService.endBattleLogging(context.getBattleId(), context);
            } catch (Exception e) {
                log.debug("Failed to end battle logging", e);
            }
        }

        if (cleanupCallback != null) {
            try {
                cleanupCallback.run();
            } catch (Exception e) {
                log.debug("Failed to execute cleanup callback", e);
            }
        }
    }
}
