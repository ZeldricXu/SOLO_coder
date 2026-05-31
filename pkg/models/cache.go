package models

import "time"

type CacheStrategy string

const (
	CacheStrategyLRU  CacheStrategy = "lru"
	CacheStrategyLFU  CacheStrategy = "lfu"
	CacheStrategyFIFO CacheStrategy = "fifo"
	CacheStrategyTTL  CacheStrategy = "ttl"
)

type CacheEntry struct {
	Key       string
	Value     interface{}
	ExpiresAt time.Time
	CreatedAt time.Time
	Hits      int
}

type CacheInvalidationEvent struct {
	Key       string
	Reason    string
	OldValue  interface{}
	Timestamp time.Time
}

type CacheStats struct {
	Size        int            `json:"size"`
	MaxSize     int            `json:"max_size"`
	Strategy    CacheStrategy  `json:"strategy"`
	DefaultTTL  int            `json:"default_ttl"`
	TotalHits   int            `json:"total_hits"`
	TagsCount   int            `json:"tags_count"`
}
