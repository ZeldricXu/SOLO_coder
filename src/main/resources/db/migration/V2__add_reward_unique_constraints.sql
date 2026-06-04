ALTER TABLE reward_records
    ADD COLUMN requestId VARCHAR(255) NULL;

ALTER TABLE reward_records
    ADD CONSTRAINT uk_player_season_type
        UNIQUE (playerId, seasonId, rewardType);

ALTER TABLE reward_records
    ADD CONSTRAINT uk_reward_request_id
        UNIQUE (requestId);

CREATE INDEX idx_reward_request_id ON reward_records(requestId);
