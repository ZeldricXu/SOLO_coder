package deploy

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/multicloud/cli/internal/cloud"
	"github.com/multicloud/cli/internal/common"
	"github.com/multicloud/cli/internal/planner"
)

type DeployStatus string

const (
	StatusPending    DeployStatus = "pending"
	StatusRunning    DeployStatus = "running"
	StatusSuccess    DeployStatus = "success"
	StatusFailed     DeployStatus = "failed"
	StatusRollback   DeployStatus = "rollback"
	StatusRolledBack DeployStatus = "rolled_back"
)

type DeployResult struct {
	ResourceName string
	Action       common.ResourceAction
	Status       DeployStatus
	Resource     *common.Resource
	Error        error
	Duration     time.Duration
	StartedAt    time.Time
	FinishedAt   time.Time
}

type DeployProgress struct {
	Total      int
	Completed  int
	Current    string
	Status     DeployStatus
	Results    []*DeployResult
	Percentage float64
}

type DeployOptions struct {
	MaxParallel      int
	EnableRollback   bool
	ProgressCallback func(*DeployProgress)
	ProviderFactory  func(common.CloudProvider) (cloud.ResourceProvider, error)
}

type Deployer struct {
	options  DeployOptions
	mu       sync.Mutex
	results  map[string]*DeployResult
	progress *DeployProgress
	cancel   context.CancelFunc
}

type ProgressBar struct {
	total     int
	completed int
	width     int
	useColor  bool
}

func NewProgressBar(total int) *ProgressBar {
	return &ProgressBar{
		total:    total,
		width:    40,
		useColor: true,
	}
}

func (pb *ProgressBar) SetUseColor(useColor bool) {
	pb.useColor = useColor
}

func (pb *ProgressBar) Update(completed int, current string) string {
	pb.completed = completed
	percentage := float64(completed) / float64(pb.total) * 100
	filled := int(float64(pb.width) * float64(completed) / float64(pb.total))

	bar := ""
	for i := 0; i < pb.width; i++ {
		if i < filled {
			bar += "="
		} else {
			bar += " "
		}
	}

	return fmt.Sprintf("\r[%s] %.1f%% (%d/%d) %s",
		bar, percentage, completed, pb.total, current)
}

func (pb *ProgressBar) Finish() string {
	return fmt.Sprintf("\nDone! %d/%d resources processed.\n", pb.completed, pb.total)
}

func NewDeployer(options DeployOptions) *Deployer {
	if options.MaxParallel <= 0 {
		options.MaxParallel = 10
	}

	return &Deployer{
		options: options,
		results: make(map[string]*DeployResult),
		progress: &DeployProgress{
			Results: make([]*DeployResult, 0),
		},
	}
}

func (d *Deployer) Execute(ctx context.Context, plan *planner.Plan) ([]*DeployResult, error) {
	ctx, cancel := context.WithCancel(ctx)
	d.cancel = cancel

	d.mu.Lock()
	d.progress.Total = len(plan.Changes)
	d.progress.Status = StatusRunning
	d.mu.Unlock()

	allResults := make([]*DeployResult, 0)
	hasErrors := false

	for stageIdx, group := range plan.ParallelGroups {
		d.verbosePrint("Executing stage %d/%d with %d resources",
			stageIdx+1, len(plan.ParallelGroups), len(group))

		results := d.executeStage(ctx, group, plan)

		for _, r := range results {
			allResults = append(allResults, r)
			if r.Error != nil {
				hasErrors = true
			}
		}

		if hasErrors && d.options.EnableRollback {
			d.verbosePrint("Error detected, initiating rollback...")
			rollbackResults := d.rollback(ctx, allResults, plan)
			allResults = append(allResults, rollbackResults...)
			return allResults, fmt.Errorf("deployment failed, rollback completed")
		}

		if hasErrors {
			break
		}
	}

	if hasErrors {
		return allResults, fmt.Errorf("deployment failed")
	}

	d.mu.Lock()
	d.progress.Status = StatusSuccess
	d.mu.Unlock()
	d.updateProgress()

	return allResults, nil
}

func (d *Deployer) executeStage(ctx context.Context, nodes []*planner.GraphNode, plan *planner.Plan) []*DeployResult {
	var wg sync.WaitGroup
	sem := make(chan struct{}, d.options.MaxParallel)
	results := make([]*DeployResult, len(nodes))
	var mu sync.Mutex

	for i, node := range nodes {
		wg.Add(1)
		sem <- struct{}{}

		go func(idx int, n *planner.GraphNode) {
			defer wg.Done()
			defer func() { <-sem }()

			result := d.executeNode(ctx, n, plan)

			mu.Lock()
			results[idx] = result
			d.results[n.Name] = result
			d.progress.Completed++
			d.progress.Current = n.Name
			d.progress.Results = append(d.progress.Results, result)
			d.progress.Percentage = float64(d.progress.Completed) / float64(d.progress.Total) * 100
			d.updateProgress()
			mu.Unlock()

		}(i, node)
	}

	wg.Wait()
	return results
}

func (d *Deployer) executeNode(ctx context.Context, node *planner.GraphNode, plan *planner.Plan) *DeployResult {
	result := &DeployResult{
		ResourceName: node.Name,
		StartedAt:    time.Now(),
		Status:       StatusRunning,
	}

	action := d.getActionForNode(node, plan)
	result.Action = action

	d.verbosePrint("Executing %s on %s", action, node.Name)

	if d.options.ProviderFactory == nil {
		result.Status = StatusFailed
		result.Error = fmt.Errorf("provider factory not configured")
		result.FinishedAt = time.Now()
		result.Duration = result.FinishedAt.Sub(result.StartedAt)
		return result
	}

	provider, err := d.options.ProviderFactory(node.Resource.Provider)
	if err != nil {
		result.Status = StatusFailed
		result.Error = err
		result.FinishedAt = time.Now()
		result.Duration = result.FinishedAt.Sub(result.StartedAt)
		return result
	}

	var resource *common.Resource

	switch action {
	case common.ActionCreate:
		resource, err = provider.CreateResource(ctx, node.Resource)
		if err != nil {
			result.Status = StatusFailed
			result.Error = err
			result.FinishedAt = time.Now()
			result.Duration = result.FinishedAt.Sub(result.StartedAt)
			return result
		}
		result.Resource = resource
		result.Status = StatusSuccess

	case common.ActionUpdate:
		resourceID := ""
		for _, change := range plan.Changes {
			if change.ResourceName == node.Name && change.Old != nil {
				resourceID = change.Old.ID
				break
			}
		}
		if resourceID == "" {
			result.Status = StatusFailed
			result.Error = fmt.Errorf("resource ID not found for update")
			result.FinishedAt = time.Now()
			result.Duration = result.FinishedAt.Sub(result.StartedAt)
			return result
		}
		resource, err = provider.UpdateResource(ctx, resourceID, node.Resource)
		if err != nil {
			result.Status = StatusFailed
			result.Error = err
			result.FinishedAt = time.Now()
			result.Duration = result.FinishedAt.Sub(result.StartedAt)
			return result
		}
		result.Resource = resource
		result.Status = StatusSuccess

	case common.ActionDelete:
		resourceID := ""
		resourceType := common.ResourceCompute
		for _, change := range plan.Changes {
			if change.ResourceName == node.Name && change.Old != nil {
				resourceID = change.Old.ID
				resourceType = change.Old.Type
				break
			}
		}
		if resourceID == "" {
			result.Status = StatusFailed
			result.Error = fmt.Errorf("resource ID not found for delete")
			result.FinishedAt = time.Now()
			result.Duration = result.FinishedAt.Sub(result.StartedAt)
			return result
		}
		err = provider.DeleteResource(ctx, resourceID, resourceType)
		if err != nil {
			result.Status = StatusFailed
			result.Error = err
			result.FinishedAt = time.Now()
			result.Duration = result.FinishedAt.Sub(result.StartedAt)
			return result
		}
		result.Status = StatusSuccess

	case common.ActionNoop:
		result.Status = StatusSuccess
	}

	result.FinishedAt = time.Now()
	result.Duration = result.FinishedAt.Sub(result.StartedAt)
	return result
}

func (d *Deployer) getActionForNode(node *planner.GraphNode, plan *planner.Plan) common.ResourceAction {
	for _, c := range plan.Create {
		if c.Name == node.Name {
			return common.ActionCreate
		}
	}
	for _, u := range plan.Update {
		if u.Name == node.Name {
			return common.ActionUpdate
		}
	}
	for _, del := range plan.Delete {
		if del.Name == node.Name {
			return common.ActionDelete
		}
	}
	return common.ActionNoop
}

func (d *Deployer) rollback(ctx context.Context, results []*DeployResult, plan *planner.Plan) []*DeployResult {
	d.mu.Lock()
	d.progress.Status = StatusRollback
	d.mu.Unlock()

	rollbackResults := make([]*DeployResult, 0)
	successfulResults := make([]*DeployResult, 0)

	for _, r := range results {
		if r.Status == StatusSuccess && r.Action != common.ActionDelete && r.Action != common.ActionNoop {
			successfulResults = append(successfulResults, r)
		}
	}

	for i := len(successfulResults) - 1; i >= 0; i-- {
		r := successfulResults[i]
		rbResult := d.rollbackResource(ctx, r, plan)
		rollbackResults = append(rollbackResults, rbResult)
	}

	d.mu.Lock()
	d.progress.Status = StatusRolledBack
	d.mu.Unlock()

	return rollbackResults
}

func (d *Deployer) rollbackResource(ctx context.Context, result *DeployResult, plan *planner.Plan) *DeployResult {
	rbResult := &DeployResult{
		ResourceName: result.ResourceName,
		Action:       common.ActionDelete,
		Status:       StatusRollback,
		StartedAt:    time.Now(),
	}

	d.verbosePrint("Rolling back %s", result.ResourceName)

	if result.Resource == nil {
		rbResult.Status = StatusRolledBack
		rbResult.FinishedAt = time.Now()
		rbResult.Duration = rbResult.FinishedAt.Sub(rbResult.StartedAt)
		return rbResult
	}

	provider, err := d.options.ProviderFactory(result.Resource.Provider)
	if err != nil {
		rbResult.Status = StatusFailed
		rbResult.Error = fmt.Errorf("rollback failed: %v", err)
		rbResult.FinishedAt = time.Now()
		rbResult.Duration = rbResult.FinishedAt.Sub(rbResult.StartedAt)
		return rbResult
	}

	err = provider.DeleteResource(ctx, result.Resource.ID, result.Resource.Type)
	if err != nil {
		rbResult.Status = StatusFailed
		rbResult.Error = fmt.Errorf("rollback failed: %v", err)
		rbResult.FinishedAt = time.Now()
		rbResult.Duration = rbResult.FinishedAt.Sub(rbResult.StartedAt)
		return rbResult
	}

	rbResult.Status = StatusRolledBack
	rbResult.FinishedAt = time.Now()
	rbResult.Duration = rbResult.FinishedAt.Sub(rbResult.StartedAt)
	return rbResult
}

func (d *Deployer) updateProgress() {
	if d.options.ProgressCallback != nil {
		d.mu.Lock()
		defer d.mu.Unlock()
		d.options.ProgressCallback(d.progress)
	}
}

func (d *Deployer) Cancel() {
	if d.cancel != nil {
		d.cancel()
	}
}

func (d *Deployer) GetResult(resourceName string) (*DeployResult, bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	r, exists := d.results[resourceName]
	return r, exists
}

func (d *Deployer) GetProgress() *DeployProgress {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.progress
}

func (d *Deployer) verbosePrint(format string, args ...interface{}) {
	if d.options.ProgressCallback != nil {
		return
	}
}

func Summary(results []*DeployResult) map[string]int {
	summary := make(map[string]int)
	for _, r := range results {
		summary[string(r.Status)]++
	}
	return summary
}

func PrintResults(results []*DeployResult, useColor bool) string {
	var sb strings.Builder

	green := func(s string) string { return s }
	red := func(s string) string { return s }
	yellow := func(s string) string { return s }
	cyan := func(s string) string { return s }

	if useColor {
		green = func(s string) string { return "\033[32m" + s + "\033[0m" }
		red = func(s string) string { return "\033[31m" + s + "\033[0m" }
		yellow = func(s string) string { return "\033[33m" + s + "\033[0m" }
		cyan = func(s string) string { return "\033[36m" + s + "\033[0m" }
	}

	sb.WriteString("\nDeployment Results:\n")
	sb.WriteString(strings.Repeat("=", 60) + "\n\n")

	for _, r := range results {
		statusStr := ""
		switch r.Status {
		case StatusSuccess:
			statusStr = green("✓ SUCCESS")
		case StatusFailed:
			statusStr = red("✗ FAILED")
		case StatusRolledBack:
			statusStr = yellow("↩ ROLLED BACK")
		case StatusRollback:
			statusStr = yellow("↩ ROLLING BACK")
		default:
			statusStr = cyan("○ " + string(r.Status))
		}

		sb.WriteString(fmt.Sprintf("%-40s %-20s %v\n",
			cyan(r.ResourceName), statusStr, r.Duration.Round(time.Millisecond)))

		if r.Error != nil {
			sb.WriteString(fmt.Sprintf("  Error: %v\n", r.Error))
		}
		if r.Resource != nil {
			sb.WriteString(fmt.Sprintf("  ID: %s\n", r.Resource.ID))
		}
	}

	summary := Summary(results)
	sb.WriteString("\nSummary:\n")
	sb.WriteString(strings.Repeat("-", 60) + "\n")
	sb.WriteString(fmt.Sprintf("  Successful: %d\n", summary[string(StatusSuccess)]))
	sb.WriteString(fmt.Sprintf("  Failed: %d\n", summary[string(StatusFailed)]))
	sb.WriteString(fmt.Sprintf("  Rolled Back: %d\n", summary[string(StatusRolledBack)]))

	total := time.Duration(0)
	for _, r := range results {
		total += r.Duration
	}
	sb.WriteString(fmt.Sprintf("  Total Duration: %v\n", total.Round(time.Millisecond)))

	return sb.String()
}
