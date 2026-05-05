package storage

import (
	"context"
	"encoding/json"
	"time"

	"gamestats/event-access/config"
	"gamestats/event-access/model"

	influxdb2 "github.com/influxdata/influxdb-client-go/v2"
	"github.com/influxdata/influxdb-client-go/v2/api"
	"go.uber.org/zap"
)

type InfluxDBClient struct {
	client   influxdb2.Client
	writeAPI api.WriteAPIBlocking
	queryAPI api.QueryAPI
	config   config.InfluxDBConfig
}

func NewInfluxDBClient(cfg config.InfluxDBConfig) (*InfluxDBClient, error) {
	client := influxdb2.NewClient(cfg.URL, cfg.Token)

	health, err := client.Health(context.Background())
	if err != nil {
		return nil, err
	}

	zap.L().Info("InfluxDB connection established", zap.String("status", string(health.Status)))

	return &InfluxDBClient{
		client:   client,
		writeAPI: client.WriteAPIBlocking(cfg.Org, cfg.Bucket),
		queryAPI: client.QueryAPI(cfg.Org),
		config:   cfg,
	}, nil
}

func (c *InfluxDBClient) Close() {
	c.client.Close()
	zap.L().Info("InfluxDB connection closed")
}

func (c *InfluxDBClient) WriteEvent(ctx context.Context, event *model.GameEvent) error {
	eventTime, err := time.Parse(time.RFC3339, event.EventTime)
	if err != nil {
		eventTime = time.Now().UTC()
	}

	eventDataJSON, _ := json.Marshal(event.EventData)

	point := influxdb2.NewPointWithMeasurement("game_events").
		AddTag("event_id", event.EventID).
		AddTag("player_id", event.PlayerID).
		AddTag("game_id", event.GameID).
		AddTag("server_id", event.ServerID).
		AddTag("event_type", event.EventType).
		AddField("event_data", string(eventDataJSON)).
		SetTime(eventTime)

	return c.writeAPI.WritePoint(ctx, point)
}

func (c *InfluxDBClient) WriteEvents(ctx context.Context, events []model.GameEvent) error {
	for _, event := range events {
		if err := c.WriteEvent(ctx, &event); err != nil {
			zap.L().Error("Failed to write event", zap.Error(err), zap.String("event_id", event.EventID))
		}
	}
	return nil
}

func (c *InfluxDBClient) WriteOnlineStats(ctx context.Context, stats *model.OnlineStats) error {
	point := influxdb2.NewPointWithMeasurement("online_stats").
		AddTag("stat_id", stats.StatID).
		AddTag("game_id", stats.GameID).
		AddField("online_count", stats.OnlineCount).
		AddField("peak_today", stats.PeakToday).
		SetTime(stats.SampleTime)

	for serverID, count := range stats.ServerDistribution {
		point.AddField("server_"+serverID, count)
	}

	return c.writeAPI.WritePoint(ctx, point)
}

func (c *InfluxDBClient) QueryOnlineStats(ctx context.Context, gameID string, start time.Time, end time.Time) ([]model.OnlineStats, error) {
	query := `
		from(bucket: "` + c.config.Bucket + `")
			|> range(start: ` + start.Format(time.RFC3339) + `, stop: ` + end.Format(time.RFC3339) + `)
			|> filter(fn: (r) => r._measurement == "online_stats" and r.game_id == "` + gameID + `")
			|> filter(fn: (r) => r._field == "online_count" or r._field == "peak_today")
			|> pivot(rowKey:["_time"], columnKey: ["_field"], valueColumn: "_value")
	`

	result, err := c.queryAPI.Query(ctx, query)
	if err != nil {
		return nil, err
	}

	var stats []model.OnlineStats
	for result.Next() {
		record := result.Record()
		stat := model.OnlineStats{
			StatID:      record.ValueByKey("stat_id").(string),
			GameID:      gameID,
			OnlineCount: int(record.ValueByKey("online_count").(int64)),
			PeakToday:   int(record.ValueByKey("peak_today").(int64)),
			SampleTime:  record.Time(),
			ServerDistribution: make(map[string]int),
		}
		stats = append(stats, stat)
	}

	if err := result.Err(); err != nil {
		return nil, err
	}

	return stats, nil
}

func (c *InfluxDBClient) QueryTrend(ctx context.Context, gameID string, duration time.Duration) ([]model.TrendPoint, error) {
	start := time.Now().Add(-duration)
	end := time.Now()

	query := `
		from(bucket: "` + c.config.Bucket + `")
			|> range(start: ` + start.Format(time.RFC3339) + `, stop: ` + end.Format(time.RFC3339) + `)
			|> filter(fn: (r) => r._measurement == "online_stats" and r.game_id == "` + gameID + `")
			|> filter(fn: (r) => r._field == "online_count")
			|> aggregateWindow(every: 1m, fn: mean, createEmpty: false)
	`

	result, err := c.queryAPI.Query(ctx, query)
	if err != nil {
		return nil, err
	}

	var trend []model.TrendPoint
	for result.Next() {
		record := result.Record()
		point := model.TrendPoint{
			Time:  record.Time(),
			Count: int(record.Value().(float64)),
		}
		trend = append(trend, point)
	}

	if err := result.Err(); err != nil {
		return nil, err
	}

	return trend, nil
}
