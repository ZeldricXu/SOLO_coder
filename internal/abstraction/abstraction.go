package abstraction

import (
	"context"
	"time"

	"github.com/chaoslab/platform/internal/common"
)

type ResourceManager interface {
	CreateResource(ctx context.Context, req *common.CreateResourceRequest) (*common.Resource, error)
	GetResourceStatus(ctx context.Context, id string) (*common.ResourceStatus, error)
	BatchOperation(ctx context.Context, ops []*common.Operation) (*common.BatchResult, error)
}

type DNSProxyService interface {
	Resolve(ctx context.Context, domain string, recordType string) (*common.DNSResponse, error)
	AddUpstream(ctx context.Context, upstream *common.DNSUpstream) error
	RemoveUpstream(ctx context.Context, name string) error
	GetUpstreams(ctx context.Context) ([]*common.DNSUpstream, error)
	ClearCache(ctx context.Context) error
	GetCacheStats(ctx context.Context) (*common.CacheStats, error)
	SetStrategy(ctx context.Context, strategy common.StrategyType) error
}

type MTLSCertificateService interface {
	IssueCertificate(ctx context.Context, req *common.CertificateRequest) (*common.Certificate, error)
	RotateCertificate(ctx context.Context, certID string) (*common.Certificate, error)
	RevokeCertificate(ctx context.Context, certID string, reason string) error
	GetCRL(ctx context.Context) (*common.CRL, error)
	SetRotationPolicy(ctx context.Context, policy *common.RotationPolicy) error
	GetCertificate(ctx context.Context, certID string) (*common.Certificate, error)
	ListCertificates(ctx context.Context, namespace string) ([]*common.Certificate, error)
}

type ChaosOrchestrator interface {
	DefineScenario(ctx context.Context, scenario *common.ChaosScenario) (*common.ChaosScenario, error)
	ExecuteScenario(ctx context.Context, scenarioID string, scope *common.InjectionScope) (*common.RunInstance, error)
	CancelExecution(ctx context.Context, runID string) error
	GetExecutionStatus(ctx context.Context, runID string) (*common.RunInstance, error)
	ListScenarios(ctx context.Context, namespace string) ([]*common.ChaosScenario, error)
	DeleteScenario(ctx context.Context, scenarioID string) error
}

type TrafficController interface {
	ConfigureCanary(ctx context.Context, cfg *common.CanaryConfig) (*common.TrafficPolicy, error)
	ConfigureBlueGreen(ctx context.Context, cfg *common.BlueGreenConfig) (*common.TrafficPolicy, error)
	ConfigureMirroring(ctx context.Context, cfg *common.MirrorConfig) (*common.TrafficPolicy, error)
	ConfigureCircuitBreaker(ctx context.Context, cfg *common.CircuitBreakerConfig) (*common.TrafficPolicy, error)
	GetPolicy(ctx context.Context, policyID string) (*common.TrafficPolicy, error)
	UpdateTrafficWeight(ctx context.Context, policyID string, weight int32) error
	DisablePolicy(ctx context.Context, policyID string) error
	ListPolicies(ctx context.Context, namespace string) ([]*common.TrafficPolicy, error)
}

type EventStore interface {
	AppendEvent(ctx context.Context, event *common.DomainEvent) error
	GetEvents(ctx context.Context, entityID string, fromVersion int64) ([]*common.DomainEvent, error)
	CreateSnapshot(ctx context.Context, entityID string, version int64, state interface{}) error
	GetSnapshot(ctx context.Context, entityID string) (*common.Snapshot, error)
	RebuildProjection(ctx context.Context, projectionType string, until time.Time) error
	TimeTravelQuery(ctx context.Context, entityID string, targetTime time.Time) (interface{}, error)
	GetEventStats(ctx context.Context) (*common.EventStats, error)
}

type ImageDistributionService interface {
	PullImage(ctx context.Context, ref string, layers []string) (*common.ImagePullResult, error)
	SyncImage(ctx context.Context, sourceRef, targetRef string) (*common.ImageSyncResult, error)
	EnableP2P(ctx context.Context, imageRef string, nodes []string) (*common.P2PStatus, error)
	GetImageManifest(ctx context.Context, ref string) (*common.ImageManifest, error)
	ListImages(ctx context.Context, registry string) ([]*common.ImageInfo, error)
	DeleteImage(ctx context.Context, ref string) error
}

type SidecarLifecycleManager interface {
	InjectSidecar(ctx context.Context, target *common.InjectionTarget, cfg *common.SidecarConfig) (*common.SidecarInstance, error)
	EjectSidecar(ctx context.Context, instanceID string) error
	HotUpdateConfig(ctx context.Context, instanceID string, newConfig *common.SidecarConfig) error
	SetResourceLimits(ctx context.Context, instanceID string, limits *common.ResourceLimits) error
	GetSidecarStatus(ctx context.Context, instanceID string) (*common.SidecarInstance, error)
	SetInjectionPolicy(ctx context.Context, policy *common.InjectionPolicy) error
	ListSidecars(ctx context.Context, namespace string) ([]*common.SidecarInstance, error)
}

type AuditService interface {
	PersistCommand(ctx context.Context, cmd *common.Command) error
	GetCommand(ctx context.Context, commandID string) (*common.Command, error)
	QueryCommands(ctx context.Context, filter *common.CommandFilter) ([]*common.Command, error)
	GenerateAuditLog(ctx context.Context, entry *common.AuditEntry) error
	GetAuditLogs(ctx context.Context, filter *common.AuditFilter) ([]*common.AuditEntry, error)
	GenerateComplianceReport(ctx context.Context, req *common.ComplianceRequest) (*common.ComplianceReport, error)
	AssociateCommandWithEvents(ctx context.Context, commandID string, eventIDs []string) error
}
