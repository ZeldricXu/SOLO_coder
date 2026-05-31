package ports

import (
	"context"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
)

type ResourceManager interface {
	CreateResource(ctx context.Context, req *domain.CreateResourceRequest) (*domain.Resource, error)
	GetResourceStatus(ctx context.Context, id string) (*domain.ResourceStatusResponse, error)
	BatchOperation(ctx context.Context, ops []*domain.Operation) (*domain.BatchResult, error)
}

type DNSProxyService interface {
	Resolve(ctx context.Context, domain string, recordType string) (*domain.DNSResponse, error)
	AddUpstream(ctx context.Context, upstream *domain.DNSUpstream) error
	RemoveUpstream(ctx context.Context, name string) error
	GetUpstreams(ctx context.Context) ([]*domain.DNSUpstream, error)
	ClearCache(ctx context.Context) error
	GetCacheStats(ctx context.Context) (*domain.CacheStats, error)
	SetStrategy(ctx context.Context, strategy domain.StrategyType) error
	WarmupCache(ctx context.Context, req *domain.CacheWarmupRequest) (*domain.MultiLevelCacheStats, error)
	InvalidateCache(ctx context.Context, req *domain.CacheInvalidationRequest) error
	GetMultiLevelCacheStats(ctx context.Context) (*domain.MultiLevelCacheStats, error)
	GetMultiLevelCache() MultiLevelDNSCache
}

type MTLSCertificateService interface {
	IssueCertificate(ctx context.Context, req *domain.CertificateRequest) (*domain.Certificate, error)
	RotateCertificate(ctx context.Context, certID string) (*domain.Certificate, error)
	RevokeCertificate(ctx context.Context, certID string, reason string) error
	GetCRL(ctx context.Context) (*domain.CRL, error)
	SetRotationPolicy(ctx context.Context, policy *domain.RotationPolicy) error
	GetCertificate(ctx context.Context, certID string) (*domain.Certificate, error)
	ListCertificates(ctx context.Context, namespace string) ([]*domain.Certificate, error)
	GetCACertificatePEM() string
	IssueCertificates(ctx context.Context, reqs []*domain.CertificateRequest) []*BatchOperationResult
	RotateCertificates(ctx context.Context, certIDs []string) []*BatchOperationResult
	RevokeCertificates(ctx context.Context, certIDs []string, reason string) []*BatchOperationResult
	GetCertificates(ctx context.Context, certIDs []string) ([]*domain.Certificate, []*BatchOperationResult)
	QueueBatchRequest(req *BatchRequest) *BatchRequestFuture
	GetBatchProcessor() BatchProcessor
}

type ChaosOrchestratorService interface {
	DefineScenario(ctx context.Context, scenario *domain.ChaosScenario) (*domain.ChaosScenario, error)
	ExecuteScenario(ctx context.Context, scenarioID string, scope *domain.InjectionScope) (*domain.RunInstance, error)
	CancelExecution(ctx context.Context, runID string) error
	GetExecutionStatus(ctx context.Context, runID string) (*domain.RunInstance, error)
	ListScenarios(ctx context.Context, namespace string) ([]*domain.ChaosScenario, error)
	DeleteScenario(ctx context.Context, scenarioID string) error
	GetActiveRunsCount() int
	GetSupportedInjectors() []string
	GetMetrics() *domain.ChaosMetrics
	GetExecutionTiming(ctx context.Context, runID string) (*domain.ExecutionMetrics, error)
}

type ChaosOrchestrator = ChaosOrchestratorService

type TrafficController interface {
	ConfigureCanary(ctx context.Context, cfg interface{}) (interface{}, error)
	ConfigureBlueGreen(ctx context.Context, cfg interface{}) (interface{}, error)
	ConfigureMirroring(ctx context.Context, cfg interface{}) (interface{}, error)
	ConfigureCircuitBreaker(ctx context.Context, cfg interface{}) (interface{}, error)
	GetPolicy(ctx context.Context, policyID string) (interface{}, error)
	UpdateTrafficWeight(ctx context.Context, policyID string, weight int32) error
	DisablePolicy(ctx context.Context, policyID string) error
	ListPolicies(ctx context.Context, namespace string) ([]interface{}, error)
}

type EventStore interface {
	AppendEvent(ctx context.Context, event interface{}) error
	GetEvents(ctx context.Context, entityID string, fromVersion int64) ([]interface{}, error)
	CreateSnapshot(ctx context.Context, entityID string, version int64, state interface{}) error
	GetSnapshot(ctx context.Context, entityID string) (*domain.Snapshot, error)
	RebuildProjection(ctx context.Context, projectionType string, until time.Time) error
	TimeTravelQuery(ctx context.Context, entityID string, targetTime time.Time) (interface{}, error)
	GetEventStats(ctx context.Context) (interface{}, error)
}

type ImageDistributionService interface {
	PullImage(ctx context.Context, ref string, layers []string) (interface{}, error)
	SyncImage(ctx context.Context, sourceRef, targetRef string) (interface{}, error)
	EnableP2P(ctx context.Context, imageRef string, nodes []string) (interface{}, error)
	GetImageManifest(ctx context.Context, ref string) (interface{}, error)
	ListImages(ctx context.Context, registry string) ([]interface{}, error)
	DeleteImage(ctx context.Context, ref string) error
}

type SidecarLifecycleManager interface {
	InjectSidecar(ctx context.Context, target interface{}, cfg interface{}) (interface{}, error)
	EjectSidecar(ctx context.Context, instanceID string) error
	HotUpdateConfig(ctx context.Context, instanceID string, newConfig interface{}) error
	SetResourceLimits(ctx context.Context, instanceID string, limits interface{}) error
	GetSidecarStatus(ctx context.Context, instanceID string) (interface{}, error)
	SetInjectionPolicy(ctx context.Context, policy interface{}) error
	ListSidecars(ctx context.Context, namespace string) ([]interface{}, error)
}

type AuditService interface {
	PersistCommand(ctx context.Context, cmd interface{}) error
	GetCommand(ctx context.Context, commandID string) (interface{}, error)
	QueryCommands(ctx context.Context, filter interface{}) ([]interface{}, error)
	GenerateAuditLog(ctx context.Context, entry interface{}) error
	GetAuditLogs(ctx context.Context, filter interface{}) ([]interface{}, error)
	GenerateComplianceReport(ctx context.Context, req interface{}) (interface{}, error)
	AssociateCommandWithEvents(ctx context.Context, commandID string, eventIDs []string) error
}
