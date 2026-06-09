package com.cardgame.ai;

import com.cardgame.common.entity.EnemyTemplate;
import com.cardgame.common.enums.BuffType;
import com.cardgame.common.enums.EffectType;
import com.cardgame.common.enums.EnemyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class EnemyTemplateLibrary {

    private final Map<String, EnemyTemplate> templateMap = new HashMap<>();
    private final Map<Integer, List<String>> floorEnemyMap = new HashMap<>();

    @PostConstruct
    public void init() {
        registerTemplate(createSlimeTemplate());
        registerTemplate(createGoblinTemplate());
        registerTemplate(createSkeletonTemplate());
        registerTemplate(createOrcTemplate());
        registerTemplate(createDarkMageTemplate());
        registerTemplate(createGolemTemplate());
        registerTemplate(createVampireTemplate());
        registerTemplate(createDragonTemplate());

        mapEnemiesToFloors();

        log.info("Loaded {} enemy templates", templateMap.size());
    }

    private void registerTemplate(EnemyTemplate template) {
        templateMap.put(template.getTemplateId(), template);
    }

    private void mapEnemiesToFloors() {
        for (int floor = 1; floor <= 50; floor++) {
            List<String> enemyIds = new ArrayList<>();

            if (floor <= 5) {
                enemyIds.add("slime");
                enemyIds.add("goblin");
            } else if (floor <= 10) {
                enemyIds.add("slime");
                enemyIds.add("goblin");
                enemyIds.add("skeleton");
            } else if (floor <= 15) {
                enemyIds.add("goblin");
                enemyIds.add("skeleton");
                enemyIds.add("orc");
            } else if (floor <= 20) {
                enemyIds.add("skeleton");
                enemyIds.add("orc");
                enemyIds.add("dark_mage");
            } else if (floor <= 25) {
                enemyIds.add("orc");
                enemyIds.add("dark_mage");
                enemyIds.add("golem");
            } else if (floor <= 30) {
                enemyIds.add("dark_mage");
                enemyIds.add("golem");
                enemyIds.add("vampire");
            } else if (floor <= 40) {
                enemyIds.add("golem");
                enemyIds.add("vampire");
            } else {
                enemyIds.add("vampire");
                enemyIds.add("dragon");
            }

            floorEnemyMap.put(floor, enemyIds);
        }
    }

    public List<EnemyTemplate> getEnemiesForFloor(int floor) {
        List<String> enemyIds = floorEnemyMap.getOrDefault(floor, floorEnemyMap.get(1));
        List<EnemyTemplate> templates = new ArrayList<>();

        for (String id : enemyIds) {
            EnemyTemplate template = templateMap.get(id);
            if (template != null) {
                templates.add(template);
            }
        }

        return templates;
    }

    public EnemyTemplate getBossForFloor(int floor) {
        if (floor % 10 == 0) {
            return templateMap.get("dragon");
        } else if (floor % 5 == 0) {
            return templateMap.get("vampire");
        }
        return null;
    }

    public EnemyTemplate getTemplate(String templateId) {
        return templateMap.get(templateId);
    }

    private EnemyTemplate createSlimeTemplate() {
        return EnemyTemplate.builder()
                .templateId("slime")
                .name("史莱姆")
                .enemyType(EnemyType.NORMAL)
                .baseHp(30)
                .baseSpeed(5)
                .baseDamage(6)
                .baseBlock(4)
                .difficultyScaling(1.1f)
                .aiBehaviorTreeId("aggressive")
                .experienceReward(10)
                .goldReward(5)
                .build();
    }

    private EnemyTemplate createGoblinTemplate() {
        return EnemyTemplate.builder()
                .templateId("goblin")
                .name("哥布林")
                .enemyType(EnemyType.NORMAL)
                .baseHp(40)
                .baseSpeed(8)
                .baseDamage(8)
                .baseBlock(3)
                .difficultyScaling(1.12f)
                .aiBehaviorTreeId("balanced")
                .experienceReward(15)
                .goldReward(8)
                .build();
    }

    private EnemyTemplate createSkeletonTemplate() {
        return EnemyTemplate.builder()
                .templateId("skeleton")
                .name("骷髅战士")
                .enemyType(EnemyType.NORMAL)
                .baseHp(50)
                .baseSpeed(6)
                .baseDamage(10)
                .baseBlock(5)
                .difficultyScaling(1.15f)
                .aiBehaviorTreeId("defensive")
                .experienceReward(20)
                .goldReward(10)
                .build();
    }

    private EnemyTemplate createOrcTemplate() {
        return EnemyTemplate.builder()
                .templateId("orc")
                .name("兽人武士")
                .enemyType(EnemyType.ELITE)
                .baseHp(80)
                .baseSpeed(7)
                .baseDamage(15)
                .baseBlock(6)
                .difficultyScaling(1.18f)
                .aiBehaviorTreeId("aggressive")
                .experienceReward(40)
                .goldReward(25)
                .build();
    }

    private EnemyTemplate createDarkMageTemplate() {
        return EnemyTemplate.builder()
                .templateId("dark_mage")
                .name("暗黑法师")
                .enemyType(EnemyType.ELITE)
                .baseHp(60)
                .baseSpeed(9)
                .baseDamage(12)
                .baseBlock(4)
                .difficultyScaling(1.2f)
                .aiBehaviorTreeId("caster")
                .experienceReward(50)
                .goldReward(30)
                .build();
    }

    private EnemyTemplate createGolemTemplate() {
        return EnemyTemplate.builder()
                .templateId("golem")
                .name("石头人")
                .enemyType(EnemyType.ELITE)
                .baseHp(120)
                .baseSpeed(3)
                .baseDamage(18)
                .baseBlock(15)
                .difficultyScaling(1.22f)
                .aiBehaviorTreeId("defensive")
                .experienceReward(60)
                .goldReward(40)
                .build();
    }

    private EnemyTemplate createVampireTemplate() {
        return EnemyTemplate.builder()
                .templateId("vampire")
                .name("吸血鬼领主")
                .enemyType(EnemyType.BOSS)
                .baseHp(200)
                .baseSpeed(10)
                .baseDamage(20)
                .baseBlock(8)
                .difficultyScaling(1.25f)
                .aiBehaviorTreeId("vampire_boss")
                .experienceReward(150)
                .goldReward(100)
                .build();
    }

    private EnemyTemplate createDragonTemplate() {
        return EnemyTemplate.builder()
                .templateId("dragon")
                .name("远古巨龙")
                .enemyType(EnemyType.BOSS)
                .baseHp(350)
                .baseSpeed(8)
                .baseDamage(30)
                .baseBlock(12)
                .difficultyScaling(1.3f)
                .aiBehaviorTreeId("dragon_boss")
                .experienceReward(300)
                .goldReward(200)
                .build();
    }
}
