package com.cardgame.common.entity;

import com.cardgame.common.enums.EnemyType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Enemy extends GameCharacter {
    private String enemyTemplateId;
    private EnemyType enemyType;
    private int difficultyModifier;
    private int floorLevel;
    @Builder.Default
    private List<Intent> intents = new ArrayList<>();
    private String aiBehaviorTreeId;
    private int experienceReward;
    private int goldReward;
    @Builder.Default
    private List<Card> rewardCards = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Intent {
        private String type;
        private int value;
        private String targetId;
        private String description;
    }

    public void generateIntent() {
    }

    public void clearIntents() {
        this.intents.clear();
    }

    public void addIntent(Intent intent) {
        this.intents.add(intent);
    }

    public int getBaseDamage() {
        return this.difficultyModifier > 0 ? 10 + this.difficultyModifier : 10;
    }

    public int getDifficultyScaling() {
        return 10;
    }
}
