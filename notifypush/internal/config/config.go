package config

type SMSConfig struct {
	Provider     string
	APIKey       string
	SignName     string
	TemplateCode string
}

type EmailConfig struct {
	SMTPHost string
	SMTPPort int
	Username string
	Password string
	FromName string
	FromAddr string
}

type AppPushConfig struct {
	Provider  string
	APIKey    string
	ProjectID string
}

type RetryConfig struct {
	MaxRetries   int
	RetryDelayMs int
}

type ServerConfig struct {
	Port int
}

type RedisConfig struct {
	Addr         string
	Password     string
	DB           int
	PoolSize     int
	PoolTimeout  int
	DialTimeout  int
	ReadTimeout  int
	WriteTimeout int
}

type StatusQueryConfig struct {
	UrgentIntervalSec     int
	HighIntervalSec       int
	MediumIntervalSec     int
	LowIntervalSec        int
	DefaultIntervalSec     int
}

type SMSQueueConfig struct {
	QueueName        string
	ProcessingQueue string
	WorkerCount      int
	BatchSize        int
	PollIntervalMs   int
}

type BatchQueueConfig struct {
	QueueName        string
	ProcessingQueue string
	WorkerCount      int
	PollIntervalMs   int
}

type Config struct {
	SMS         SMSConfig
	Email       EmailConfig
	AppPush     AppPushConfig
	Retry       RetryConfig
	Server      ServerConfig
	Redis       RedisConfig
	StatusQuery StatusQueryConfig
	SMSQueue    SMSQueueConfig
	BatchQueue  BatchQueueConfig
}

func DefaultConfig() *Config {
	return &Config{
		SMS: SMSConfig{
			Provider:     "aliyun",
			APIKey:       "your-sms-api-key",
			SignName:     "系统通知",
			TemplateCode: "SMS_123456789",
		},
		Email: EmailConfig{
			SMTPHost: "smtp.example.com",
			SMTPPort: 587,
			Username: "user@example.com",
			Password: "password",
			FromName: "NotifyPush",
			FromAddr: "noreply@example.com",
		},
		AppPush: AppPushConfig{
			Provider:  "firebase",
			APIKey:    "your-firebase-api-key",
			ProjectID: "your-project-id",
		},
		Retry: RetryConfig{
			MaxRetries:   3,
			RetryDelayMs: 1000,
		},
		Server: ServerConfig{
			Port: 8080,
		},
		Redis: RedisConfig{
			Addr:         "localhost:6379",
			Password:     "",
			DB:           0,
			PoolSize:     10,
			PoolTimeout:  4,
			DialTimeout:  5,
			ReadTimeout:  3,
			WriteTimeout: 3,
		},
		StatusQuery: StatusQueryConfig{
			UrgentIntervalSec: 60,
			HighIntervalSec:   120,
			MediumIntervalSec: 300,
			LowIntervalSec:  600,
			DefaultIntervalSec: 180,
		},
		SMSQueue: SMSQueueConfig{
			QueueName:        "notifypush:sms:queue",
			ProcessingQueue: "notifypush:sms:processing",
			WorkerCount:      5,
			BatchSize:        10,
			PollIntervalMs:   100,
		},
		BatchQueue: BatchQueueConfig{
			QueueName:        "notifypush:batch:queue",
			ProcessingQueue: "notifypush:batch:processing",
			WorkerCount:      3,
			PollIntervalMs:   200,
		},
	}
}
