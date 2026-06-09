package com.cardgame.common.entity;

import com.cardgame.common.enums.EnemyType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnemyTemplate {
    private String id;
    private String name;
    private int baseHp;
    private int baseSpeed;
    private EnemyType enemyType;
    private String aiBehaviorTreeId;
    private int minFloor;
    private int maxFloor;
    private int experienceReward;
    private int baseGoldReward;

    public Enemy createEnemy(int floorLevel) {
        int hpMultiplier = 1 + (floorLevel - 1) / 3;
        int speedBonus = (floorLevel - 1) / 5;
        return Enemy.builder()
                .id(java.util.UUID.randomUUID().toString())
                .enemyTemplateId(this.id)
                .name(this.name)
                .enemyType(this.enemyType)
                .maxHp(this.baseHp * hpMultiplier)
                .currentHp(this.baseHp * hpMultiplier)
                .baseSpeed(this.baseSpeed + speedBonus)
                .speed(this.baseSpeed + speedBonus)
                .floorLevel(floorLevel)
                .difficultyModifier(floorLevel)
                .aiBehaviorTreeId(this.aiBehaviorTreeId)
                .experienceReward(this.experienceReward + floorLevel * 10)
                .goldReward(this.baseGoldReward + floorLevel * 5)
                .build();
    }
}
