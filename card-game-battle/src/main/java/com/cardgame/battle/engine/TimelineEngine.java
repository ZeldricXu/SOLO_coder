package com.cardgame.battle.engine;

import com.cardgame.battle.entity.BattleContext;
import com.cardgame.battle.entity.TimelineEntry;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.GameCharacter;
import com.cardgame.common.entity.Player;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class TimelineEngine {

    public void buildTimeline(BattleContext context) {
        List<TimelineEntry> timeline = new ArrayList<>();
        int order = 0;

        for (Player player : context.getPlayers()) {
            if (player.isAlive()) {
                int currentSpeed = player.getBaseSpeed() + calculateSpeedModifier(player);
                player.setSpeed(currentSpeed);

                TimelineEntry entry = TimelineEntry.builder()
                        .characterId(player.getPlayerId())
                        .characterName(player.getName())
                        .isPlayer(true)
                        .speed(currentSpeed)
                        .turnOrder(order++)
                        .hasActed(false)
                        .character(player)
                        .build();
                timeline.add(entry);
                context.getCharacterMap().put(player.getPlayerId(), player);
            }
        }

        for (Enemy enemy : context.getEnemies()) {
            if (enemy.isAlive()) {
                int currentSpeed = enemy.getBaseSpeed() + calculateSpeedModifier(enemy);
                enemy.setSpeed(currentSpeed);

                TimelineEntry entry = TimelineEntry.builder()
                        .characterId(enemy.getId())
                        .characterName(enemy.getName())
                        .isPlayer(false)
                        .speed(currentSpeed)
                        .turnOrder(order++)
                        .hasActed(false)
                        .character(enemy)
                        .build();
                timeline.add(entry);
                context.getCharacterMap().put(enemy.getId(), enemy);
            }
        }

        Collections.sort(timeline);
        context.setTimeline(timeline);

        log.debug("Built timeline with {} entries for battle {}", timeline.size(), context.getBattleId());
    }

    private int calculateSpeedModifier(GameCharacter character) {
        int modifier = 0;

        int haste = character.getBuffStacks(com.cardgame.common.enums.BuffType.HASTE.name());
        modifier += haste * 2;

        return modifier;
    }

    public void refreshTimeline(BattleContext context) {
        for (TimelineEntry entry : context.getTimeline()) {
            GameCharacter character = entry.getCharacter();
            if (character != null) {
                int currentSpeed = character.getBaseSpeed() + calculateSpeedModifier(character);
                character.setSpeed(currentSpeed);
                entry.setSpeed(currentSpeed);
                entry.setHasActed(false);
            }
        }

        List<TimelineEntry> aliveEntries = context.getTimeline().stream()
                .filter(e -> e.getCharacter() != null && e.getCharacter().isAlive())
                .toList();

        context.setTimeline(new ArrayList<>(aliveEntries));
        Collections.sort(context.getTimeline());

        if (!context.getTimeline().isEmpty()) {
            TimelineEntry first = context.getTimeline().get(0);
            context.setCurrentActorId(first.getCharacterId());
        }
    }

    public TimelineEntry getNextActor(BattleContext context) {
        for (TimelineEntry entry : context.getTimeline()) {
            if (!entry.isHasActed() && entry.getCharacter() != null && entry.getCharacter().isAlive()) {
                return entry;
            }
        }
        return null;
    }

    public void markActed(BattleContext context, String characterId) {
        for (TimelineEntry entry : context.getTimeline()) {
            if (entry.getCharacterId().equals(characterId)) {
                entry.setHasActed(true);
                entry.setActionTime(System.currentTimeMillis());
                break;
            }
        }
    }

    public boolean allActed(BattleContext context) {
        return context.getTimeline().stream()
                .filter(e -> e.getCharacter() != null && e.getCharacter().isAlive())
                .allMatch(TimelineEntry::isHasActed);
    }

    public boolean isPlayerTurn(BattleContext context) {
        TimelineEntry current = getCurrentActor(context);
        return current != null && current.isPlayer();
    }

    public TimelineEntry getCurrentActor(BattleContext context) {
        return context.getTimeline().stream()
                .filter(e -> !e.isHasActed() && e.getCharacter() != null && e.getCharacter().isAlive())
                .findFirst()
                .orElse(null);
    }

    public void advanceToNextActor(BattleContext context) {
        TimelineEntry current = getCurrentActor(context);
        if (current != null) {
            markActed(context, current.getCharacterId());
        }

        TimelineEntry next = getNextActor(context);
        if (next != null) {
            context.setCurrentActorId(next.getCharacterId());
        }
    }

    public void startNewRound(BattleContext context) {
        context.setCurrentRound(context.getCurrentRound() + 1);
        context.setCurrentTurn(1);
        refreshTimeline(context);

        TimelineEntry first = getCurrentActor(context);
        if (first != null) {
            context.setCurrentActorId(first.getCharacterId());
        }
    }
}
