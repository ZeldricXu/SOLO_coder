package data

import "time"

type Migration struct {
	ID          string    `json:"id"`
	Version     string    `json:"version"`
	Description string    `json:"description"`
	UpSQL       string    `json:"up_sql"`
	DownSQL     string    `json:"down_sql"`
	AppliedAt   time.Time `json:"applied_at,omitempty"`
}

type SchemaVersion struct {
	Version     string    `json:"version"`
	AppliedAt   time.Time `json:"applied_at"`
	Description string    `json:"description,omitempty"`
}

type QueryOptions struct {
	Limit      int                    `json:"limit"`
	Offset     int                    `json:"offset"`
	Filters    map[string]interface{} `json:"filters"`
	SortBy     string                 `json:"sort_by"`
	SortOrder  string                 `json:"sort_order"`
}

type PaginatedResult struct {
	Items      []interface{} `json:"items"`
	Total      int64         `json:"total"`
	Page       int           `json:"page"`
	PageSize   int           `json:"page_size"`
	TotalPages int           `json:"total_pages"`
}
