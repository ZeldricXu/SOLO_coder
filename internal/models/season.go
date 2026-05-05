package models

import (
	"encoding/json"
	"time"

	"gorm.io/gorm"
)

type SeasonStatus string

const (
	SeasonStatusPending SeasonStatus = "pending"
	SeasonStatusActive  SeasonStatus = "active"
	SeasonStatusEnded   SeasonStatus = "ended"
	SeasonStatusArchived SeasonStatus = "archived"
)

type Season struct {
	ID           uint           `json:"-" gorm:"primaryKey"`
	SeasonID     string         `json:"season_id" gorm:"uniqueIndex;size:64"`
	GameID       string         `json:"game_id" gorm:"index;size:64"`
	SeasonName   string         `json:"season_name" gorm:"size:128"`
	StartTime    time.Time      `json:"start_time"`
	EndTime      time.Time      `json:"end_time"`
	Status       SeasonStatus   `json:"status" gorm:"size:32;index"`
	RewardConfig string         `json:"-" gorm:"type:text"`
	CreatedAt    time.Time      `json:"-"`
	UpdatedAt    time.Time      `json:"-"`
	DeletedAt    gorm.DeletedAt `json:"-" gorm:"index"`
}

type RewardConfig struct {
	Top10  map[string]interface{} `json:"top_10,omitempty"`
	Top100 map[string]interface{} `json:"top_100,omitempty"`
	Custom map[string]interface{} `json:"custom,omitempty"`
}

func (Season) TableName() string {
	return "seasons"
}

func (s *Season) GetRewardConfig() (*RewardConfig, error) {
	if s.RewardConfig == "" {
		return &RewardConfig{}, nil
	}
	var config RewardConfig
	err := json.Unmarshal([]byte(s.RewardConfig), &config)
	if err != nil {
		return nil, err
	}
	return &config, nil
}

func (s *Season) SetRewardConfig(config *RewardConfig) error {
	if config == nil {
		s.RewardConfig = ""
		return nil
	}
	data, err := json.Marshal(config)
	if err != nil {
		return err
	}
	s.RewardConfig = string(data)
	return nil
}
