package com.cardgame.battle.effects;

import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.entity.Effect;
import com.cardgame.common.entity.GameCharacter;
import com.cardgame.common.entity.Player;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DrawCardEffect implements IEffect {

    @Override
    public void apply(BattleContext context, GameCharacter source, GameCharacter target,
                      Effect effectConfig, BattleAction action) {
        if (source instanceof Player player) {
            int drawn = 0;
            for (int i = 0; i < effectConfig.getValue(); i++) {
                if (player.getCurrentHand().size() < player.getHandLimit()) {
                    player.drawCards(1);
                    drawn++;
                }
            }
            action.setCardsDrawn(action.getCardsDrawn() + drawn);
            action.setDescription(source.getName() + " draws " + drawn + " cards");
            log.debug("{} drew {} cards", source.getName(), drawn);
        }
    }

    @Override
    public boolean requiresTarget() {
        return false;
    }
}
