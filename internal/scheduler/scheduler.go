package scheduler

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"time"

	"github.com/sourcegraph/conc/pool"
	"github.com/solocoder/cloudci/internal/artifact"
	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/internal/config"
	"github.com/solocoder/cloudci/internal/logger"
	"github.com/solocoder/cloudci/internal/logstore"
	"github.com/solocoder/cloudci/internal/models"
	"github.com/solocoder/cloudci/internal/notify"
	"github.com/solocoder/cloudci/internal/plugin"
	"github.com/solocoder/cloudci/internal/secret"
	"github.com/solocoder/cloudci/internal/storage"
	"go.uber.org/zap"
	"gorm.io/datatypes"
	"gorm.io/gorm"
)

type Scheduler struct {
	cfg                *config.SchedulerConfig
	db                 *gorm.DB
	redisClient        *storage.RedisClient
	minioClient        *storage.MinIOClient
	pluginMgr          *plugin.PluginManager
	secretMgr          *secret.SecretManager
	notifier           *notify.Notifier
	logStore           *logstore.LogStore
	artifactMgr        *artifact.ArtifactManager
	execPool           *pool.Pool
	stagePool          *pool.Pool
	runningExecs       map[types.ID]context.CancelFunc
	asyncExecutor      *AsyncPluginExecutor
	conditionEvaluator *ConditionEvaluator
	mu                 sync.RWMutex
}

type StageResult struct {
	StageID  types.ID
	Status   types.StageStatus
	Output   map[string]string
	Error    string
	ExitCode int
	Duration int64
}

func NewScheduler(cfg *config.SchedulerConfig, pluginMgr *plugin.PluginManager) *Scheduler {
	s := &Scheduler{
		cfg:                cfg,
		db:                 storage.GetDB(),
		redisClient:        &storage.RedisClient{},
		execPool:           pool.New().WithMaxGoroutines(cfg.MaxConcurrent),
		stagePool:          pool.New().WithMaxGoroutines(cfg.MaxConcurrent * 5),
		runningExecs:       make(map[types.ID]context.CancelFunc),
		pluginMgr:          pluginMgr,
		conditionEvaluator: NewConditionEvaluator(),
	}
	s.asyncExecutor = NewAsyncPluginExecutor(pluginMgr, nil)
	return s
}

func (s *Scheduler) SetSecretManager(mgr *secret.SecretManager) {
	s.secretMgr = mgr
}

func (s *Scheduler) SetNotifier(n *notify.Notifier) {
	s.notifier = n
}

func (s *Scheduler) SetLogStore(ls *logstore.LogStore) {
	s.logStore = ls
	if s.asyncExecutor != nil {
		s.asyncExecutor.logStore = ls
	}
}

func (s *Scheduler) SetArtifactManager(am *artifact.ArtifactManager) {
	s.artifactMgr = am
}

func (s *Scheduler) Start() {
	go s.processQueue()
	go s.monitorTimeouts()
	go s.cleanupExpired()
	logger.Info("scheduler started",
		zap.Int("max_concurrent", s.cfg.MaxConcurrent),
		zap.Int("queue_size", s.cfg.QueueSize))
}

func (s *Scheduler) processQueue() {
	for {
		ctx := context.Background()
		payload, err := s.redisClient.Dequeue(ctx, "executions", 5*time.Second)
		if err != nil {
			logger.Error("failed to dequeue execution", zap.Error(err))
			time.Sleep(time.Second)
			continue
		}

		if payload == "" {
			continue
		}

		var queueItem struct {
			PipelineID types.ID              `json:"pipeline_id"`
			Event      *types.InternalEvent `json:"event"`
		}

		if err := json.Unmarshal([]byte(payload), &queueItem); err != nil {
			logger.Error("failed to parse queue item", zap.Error(err))
			continue
		}

		s.execPool.Go(func() {
			s.executePipeline(ctx, queueItem.PipelineID, queueItem.Event)
		})
	}
}

func (s *Scheduler) executePipeline(ctx context.Context, pipelineID types.ID, event *types.InternalEvent) {
	var pipeline models.Pipeline
	if err := s.db.First(&pipeline, "id = ?", pipelineID).Error; err != nil {
		logger.Error("pipeline not found", zap.String("id", string(pipelineID)), zap.Error(err))
		return
	}

	def, err := pipeline.GetDefinition()
	if err != nil {
		logger.Error("failed to parse pipeline definition", zap.Error(err))
		return
	}

	execCtx, cancel := context.WithCancel(ctx)
	s.mu.Lock()
	s.runningExecs[pipeline.ID] = cancel
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.runningExecs, pipeline.ID)
		s.mu.Unlock()
		cancel()
	}()

	execution := &models.PipelineExecution{
		ID:            types.ID(types.NewID()),
		PipelineID:    pipeline.ID,
		PipelineName:  pipeline.Name,
		Status:        types.ExecutionStatusQueued,
		ProjectID:     pipeline.ProjectID,
		TriggerSource: event.EventSource,
		TriggerType:   event.EventType,
		Commit:        event.Commit,
		Branch:        event.Branch,
		Tag:           event.Tag,
		Ref:           event.Ref,
		Message:       event.Message,
		Author:        event.Author,
		AuthorEmail:   event.AuthorEmail,
		EventID:       event.ID,
	}

	now := time.Now()
	execution.QueuedAt = &now

	timeoutSecs := s.cfg.DefaultTimeout
	if def.Global != nil && def.Global.Timeout != nil {
		timeoutSecs = *def.Global.Timeout
	}
	timeoutAt := now.Add(time.Duration(timeoutSecs) * time.Second)
	execution.TimeoutAt = &timeoutAt

	if err := s.db.Create(execution).Error; err != nil {
		logger.Error("failed to create execution", zap.Error(err))
		return
	}

	logger.Info("execution started",
		zap.String("execution_id", string(execution.ID)),
		zap.String("pipeline", pipeline.Name))

	s.logStore.AppendLog(execution.ID, "", "INFO", "Pipeline execution started", "")

	stageExecs, err := s.createStageExecutions(execution.ID, def.Stages)
	if err != nil {
		s.failExecution(execution, "failed to create stage executions", err)
		return
	}

	execution.Status = types.ExecutionStatusRunning
	execution.StartedAt = timePtr(time.Now())
	s.db.Save(execution)

	results := s.executeStages(execCtx, execution, def, stageExecs)

	s.finalizeExecution(execution, results)

	if s.notifier != nil {
		severity := types.NotificationSeverityInfo
		if execution.Status == types.ExecutionStatusFailed {
			severity = types.NotificationSeverityError
		}
		s.notifier.Notify(execution, severity)
	}
}

func (s *Scheduler) createStageExecutions(executionID types.ID, stages []types.StageDefinition) (map[string]*models.StageExecution, error) {
	stageExecs := make(map[string]*models.StageExecution)

	for _, stageDef := range stages {
		maxAttempts := 1
		if stageDef.Retry != nil {
			maxAttempts = stageDef.Retry.MaxAttempts
		}

		se := &models.StageExecution{
			ID:            types.ID(types.NewID()),
			ExecutionID:   executionID,
			StageName:     stageDef.Name,
			StageType:     stageDef.Type,
			Status:        types.StageStatusPending,
			DependsOn:     mustJSON(stageDef.DependsOn),
			Env:           mustJSON(convertEnvVars(stageDef.Env)),
			Commands:      mustJSON(stageDef.Commands),
			MaxAttempts:   maxAttempts,
			AllowFailure:  stageDef.AllowFailure,
		}

		if stageDef.Plugin != nil {
			se.PluginName = stageDef.Plugin.Name
			se.PluginVersion = stageDef.Plugin.Version
		}

		if stageDef.Image != "" {
			se.Image = stageDef.Image
		}

		if err := s.db.Create(se).Error; err != nil {
			return nil, err
		}

		stageExecs[stageDef.Name] = se
	}

	return stageExecs, nil
}

func (s *Scheduler) executeStages(ctx context.Context, exec *models.PipelineExecution, def *types.PipelineDefinition, stageExecs map[string]*models.StageExecution) map[string]StageResult {
	results := make(map[string]StageResult)
	completed := make(map[string]bool)
	failed := make(map[string]bool)
	skipped := make(map[string]bool)
	var mu sync.Mutex
	var wg sync.WaitGroup

	stageResults := make(chan StageResult, len(def.Stages))

	for {
		readyStages := s.findReadyStages(def.Stages, completed, failed, skipped)

		if len(readyStages) == 0 {
			break
		}

		for _, stageDef := range readyStages {
			shouldRun, skipReason, err := s.checkStageCondition(stageDef, results, exec)
			if err != nil {
				logger.Warn("condition evaluation failed",
					zap.String("stage", stageDef.Name),
					zap.String("execution_id", string(exec.ID)),
					zap.Error(err))
				s.logStore.AppendLog(exec.ID, stageExecs[stageDef.Name].ID, "WARN",
					fmt.Sprintf("Condition evaluation failed: %v, running stage anyway", err), "")
				shouldRun = true
			}

			if !shouldRun {
				mu.Lock()
				skipped[stageDef.Name] = true
				completed[stageDef.Name] = true
				se := stageExecs[stageDef.Name]
				se.Status = types.StageStatusSkipped
				se.CompletedAt = timePtr(time.Now())
				s.db.Save(se)
				results[stageDef.Name] = StageResult{
					StageID: se.ID,
					Status:  types.StageStatusSkipped,
				}
				s.logStore.AppendLog(exec.ID, se.ID, "INFO",
					fmt.Sprintf("Stage skipped: %s", skipReason), "")
				mu.Unlock()
				continue
			}

			wg.Add(1)
			go func(sd types.StageDefinition) {
				defer wg.Done()

				select {
				case <-ctx.Done():
					mu.Lock()
					results[sd.Name] = StageResult{Status: types.StageStatusCancelled}
					completed[sd.Name] = true
					mu.Unlock()
					return
				default:
				}

				result := s.executeStage(ctx, exec, def, sd, stageExecs[sd.Name], results)

				mu.Lock()
				results[sd.Name] = result
				completed[sd.Name] = true
				if result.Status != types.StageStatusSuccess && !sd.AllowFailure {
					failed[sd.Name] = true
				}
				mu.Unlock()

				stageResults <- result
			}(stageDef)
		}

		wg.Wait()
	}

	close(stageResults)
	return results
}

func (s *Scheduler) findReadyStages(stages []types.StageDefinition, completed, failed, skipped map[string]bool) []types.StageDefinition {
	var ready []types.StageDefinition

	for _, stage := range stages {
		if completed[stage.Name] || failed[stage.Name] || skipped[stage.Name] {
			continue
		}

		allDepsCompleted := true
		anyDepFailed := false

		for _, dep := range stage.DependsOn {
			if failed[dep] {
				anyDepFailed = true
				break
			}
			if !completed[dep] && !skipped[dep] {
				allDepsCompleted = false
				break
			}
		}

		if anyDepFailed {
			completed[stage.Name] = true
			continue
		}

		if allDepsCompleted {
			ready = append(ready, stage)
		}
	}

	return ready
}

func (s *Scheduler) checkStageCondition(
	stageDef types.StageDefinition,
	results map[string]StageResult,
	exec *models.PipelineExecution,
) (bool, string, error) {
	if s.conditionEvaluator == nil {
		return true, "", nil
	}

	variables := s.buildVariables(exec, stageDef, results)

	event := &types.InternalEvent{
		ProjectID:   exec.ProjectID,
		Commit:      exec.Commit,
		Branch:      exec.Branch,
		Tag:         exec.Tag,
		Ref:         exec.Ref,
		Message:     exec.Message,
		Author:      exec.Author,
		AuthorEmail: exec.AuthorEmail,
		EventSource: exec.TriggerSource,
		EventType:   exec.TriggerType,
	}

	return s.conditionEvaluator.EvaluateStageCondition(stageDef, results, variables, event)
}

func (s *Scheduler) executeStage(ctx context.Context, exec *models.PipelineExecution, def *types.PipelineDefinition, stageDef types.StageDefinition, se *models.StageExecution, results map[string]StageResult) StageResult {
	se.Status = types.StageStatusRunning
	se.StartedAt = timePtr(time.Now())
	s.db.Save(se)

	s.logStore.AppendLog(exec.ID, se.ID, "INFO", fmt.Sprintf("Stage '%s' started", stageDef.Name), "")

	var timeout time.Duration
	if stageDef.Timeout != nil {
		timeout = time.Duration(*stageDef.Timeout) * time.Second
	} else if def.Global != nil && def.Global.Timeout != nil {
		timeout = time.Duration(*def.Global.Timeout) * time.Second
	} else {
		timeout = time.Duration(s.cfg.DefaultTimeout) * time.Second
	}

	timeoutAt := se.StartedAt.Add(timeout)
	se.TimeoutAt = &timeoutAt
	s.db.Save(se)

	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	workingDir := filepath.Join(os.TempDir(), "cloudci", string(exec.ID), stageDef.Name)
	os.MkdirAll(workingDir, 0755)
	defer os.RemoveAll(workingDir)

	variables := s.buildVariables(exec, stageDef, results)

	secrets, err := s.secretMgr.ResolveSecrets(ctx, string(exec.PipelineID), string(exec.ID), stageDef.Name, stageDef.Secrets)
	if err != nil {
		return s.failStage(se, fmt.Sprintf("failed to resolve secrets: %v", err))
	}

	env := s.buildEnv(exec, stageDef, variables, secrets)

	if stageDef.Plugin != nil {
		return s.executePluginStage(ctx, exec, stageDef, se, workingDir, variables, secrets, env)
	}

	return s.executeCommandStage(ctx, exec, stageDef, se, workingDir, env)
}

func (s *Scheduler) executePluginStage(ctx context.Context, exec *models.PipelineExecution, stageDef types.StageDefinition, se *models.StageExecution, workingDir string, variables map[string]string, secrets map[string]string, env map[string]string) StageResult {
	pluginConfig := make(map[string]string)
	for k, v := range stageDef.Plugin.Config {
		pluginConfig[k] = fmt.Sprintf("%v", v)
	}

	secretsBytes := make(map[string][]byte)
	for k, v := range secrets {
		secretsBytes[k] = []byte(v)
	}

	req := &plugin.StageContext{
		ExecutionID:  string(exec.ID),
		PipelineID:   string(exec.PipelineID),
		StageName:    stageDef.Name,
		StageType:    plugin.ConvertStageType(stageDef.Type),
		WorkingDir:   workingDir,
		Env:          env,
		Variables:    variables,
		Secrets:      secretsBytes,
		PluginConfig: pluginConfig,
		Commands:     stageDef.Commands,
		TimeoutSecs:  int64(time.Until(*se.TimeoutAt).Seconds()),
		Attempt:      int32(se.Attempt),
		MaxAttempts:  int32(se.MaxAttempts),
	}

	logCallback := func(log *plugin.LogEntry) {
		s.logStore.AppendLog(exec.ID, se.ID, log.Level, log.Message, log.Stream)
	}

	var resp *plugin.ExecuteResponse
	var err error

	if s.asyncExecutor != nil {
		resp, err = s.asyncExecutor.Execute(ctx, exec, stageDef, se, req, logCallback)
	} else {
		client, clientErr := s.pluginMgr.GetClient(ctx, stageDef.Plugin.Name, stageDef.Plugin.Version)
		if clientErr != nil {
			return s.failStage(se, fmt.Sprintf("plugin not available: %v", clientErr))
		}
		resp, err = client.Execute(ctx, req, logCallback)
	}

	if err != nil {
		return s.failStage(se, fmt.Sprintf("plugin execution failed: %v", err))
	}

	se.Status = plugin.ConvertFromStageStatus(resp.Status)
	se.ExitCode = intPtr(int(resp.ExitCode))
	se.Output = mustJSON(resp.Output)
	se.DurationSec = int64Ptr(resp.DurationMs / 1000)

	if resp.Error != "" {
		se.Error = resp.Error
	}

	se.CompletedAt = timePtr(time.Now())
	s.db.Save(se)

	if se.Status == types.StageStatusSuccess && s.artifactMgr != nil {
		for _, artifact := range resp.Artifacts {
			s.artifactMgr.UploadArtifact(ctx, exec, se, artifact.Path)
		}
	}

	s.logStore.AppendLog(exec.ID, se.ID, "INFO", fmt.Sprintf("Stage '%s' completed with status: %s", stageDef.Name, se.Status), "")

	return StageResult{
		StageID:  se.ID,
		Status:   se.Status,
		Output:   resp.Output,
		Error:    se.Error,
		ExitCode: int(resp.ExitCode),
		Duration: resp.DurationMs / 1000,
	}
}

func (s *Scheduler) executeCommandStage(ctx context.Context, exec *models.PipelineExecution, stageDef types.StageDefinition, se *models.StageExecution, workingDir string, env map[string]string) StageResult {
	s.logStore.AppendLog(exec.ID, se.ID, "INFO", "Executing commands...", "stdout")

	cmdEnv := make([]string, 0, len(env))
	for k, v := range env {
		cmdEnv = append(cmdEnv, fmt.Sprintf("%s=%s", k, v))
	}

	for i, cmd := range stageDef.Commands {
		select {
		case <-ctx.Done():
			return s.failStage(se, "stage timeout")
		default:
		}

		s.logStore.AppendLog(exec.ID, se.ID, "INFO", fmt.Sprintf("$ %s", cmd), "stdout")

		execCmd := createCommand(ctx, cmd, workingDir, cmdEnv)
		execCmd.Stdout = &logWriter{execID: exec.ID, stageID: se.ID, stream: "stdout", logStore: s.logStore}
		execCmd.Stderr = &logWriter{execID: exec.ID, stageID: se.ID, stream: "stderr", logStore: s.logStore}

		err := execCmd.Run()

		if err != nil {
			if ctx.Err() != nil {
				return s.failStage(se, "stage timeout")
			}

			if se.Attempt < se.MaxAttempts {
				se.Attempt++
				s.logStore.AppendLog(exec.ID, se.ID, "WARN",
					fmt.Sprintf("Command failed, retrying (%d/%d)...", se.Attempt, se.MaxAttempts), "")
				i--
				continue
			}

			return s.failStage(se, fmt.Sprintf("command failed: %v", err))
		}
	}

	se.Status = types.StageStatusSuccess
	se.CompletedAt = timePtr(time.Now())
	duration := se.CompletedAt.Sub(*se.StartedAt).Milliseconds() / 1000
	se.DurationSec = int64Ptr(duration)
	s.db.Save(se)

	s.logStore.AppendLog(exec.ID, se.ID, "INFO", fmt.Sprintf("Stage '%s' completed successfully", stageDef.Name), "")

	return StageResult{
		StageID:  se.ID,
		Status:   se.Status,
		Duration: duration,
	}
}

func (s *Scheduler) buildVariables(exec *models.PipelineExecution, stageDef types.StageDefinition, results map[string]StageResult) map[string]string {
	vars := make(map[string]string)

	vars["CI_EXECUTION_ID"] = string(exec.ID)
	vars["CI_PIPELINE_ID"] = string(exec.PipelineID)
	vars["CI_PIPELINE_NAME"] = exec.PipelineName
	vars["CI_STAGE_NAME"] = stageDef.Name
	vars["CI_COMMIT"] = exec.Commit
	vars["CI_BRANCH"] = exec.Branch
	vars["CI_TAG"] = exec.Tag
	vars["CI_REF"] = exec.Ref
	vars["CI_PROJECT_ID"] = exec.ProjectID
	vars["CI_TRIGGER_SOURCE"] = string(exec.TriggerSource)
	vars["CI_TRIGGER_TYPE"] = string(exec.TriggerType)

	if stageName, ok := results[""]; ok {
		for k, v := range stageName.Output {
			vars[k] = v
		}
	}

	return vars
}

func (s *Scheduler) buildEnv(exec *models.PipelineExecution, stageDef types.StageDefinition, vars map[string]string, secrets map[string]string) map[string]string {
	env := make(map[string]string)

	for k, v := range vars {
		env[k] = v
	}

	for k, v := range secrets {
		env[k] = v
	}

	for _, e := range stageDef.Env {
		if e.From != "" {
			if v, ok := vars[e.From]; ok {
				env[e.Name] = v
			}
		} else {
			env[e.Name] = e.Value
		}
	}

	return env
}

func (s *Scheduler) failStage(se *models.StageExecution, errMsg string) StageResult {
	se.Status = types.StageStatusFailed
	se.Error = errMsg
	se.CompletedAt = timePtr(time.Now())
	if se.StartedAt != nil {
		duration := se.CompletedAt.Sub(*se.StartedAt).Milliseconds() / 1000
		se.DurationSec = int64Ptr(duration)
	}
	s.db.Save(se)

	return StageResult{
		StageID: se.ID,
		Status:  se.Status,
		Error:   errMsg,
	}
}

func (s *Scheduler) failExecution(exec *models.PipelineExecution, msg string, err error) {
	exec.Status = types.ExecutionStatusFailed
	exec.Error = fmt.Sprintf("%s: %v", msg, err)
	exec.CompletedAt = timePtr(time.Now())
	if exec.StartedAt != nil {
		duration := exec.CompletedAt.Sub(*exec.StartedAt).Milliseconds() / 1000
		exec.DurationSec = int64Ptr(duration)
	}
	s.db.Save(exec)

	s.logStore.AppendLog(exec.ID, "", "ERROR", exec.Error, "")
}

func (s *Scheduler) finalizeExecution(exec *models.PipelineExecution, results map[string]StageResult) {
	hasFailure := false
	for name, result := range results {
		if result.Status == types.StageStatusFailed {
			var se models.StageExecution
			s.db.Where("execution_id = ? AND stage_name = ?", exec.ID, name).First(&se)
			if !se.AllowFailure {
				hasFailure = true
			}
		}
	}

	if hasFailure {
		exec.Status = types.ExecutionStatusFailed
	} else {
		exec.Status = types.ExecutionStatusSuccess
	}

	exec.CompletedAt = timePtr(time.Now())
	if exec.StartedAt != nil {
		duration := exec.CompletedAt.Sub(*exec.StartedAt).Milliseconds() / 1000
		exec.DurationSec = int64Ptr(duration)
	}
	s.db.Save(exec)

	s.logStore.AppendLog(exec.ID, "", "INFO",
		fmt.Sprintf("Pipeline execution completed with status: %s", exec.Status), "")

	logger.Info("execution completed",
		zap.String("execution_id", string(exec.ID)),
		zap.String("status", string(exec.Status)))
}

func (s *Scheduler) CancelExecution(executionID types.ID) error {
	s.mu.RLock()
	cancel, ok := s.runningExecs[executionID]
	s.mu.RUnlock()

	if !ok {
		return fmt.Errorf("execution not running")
	}

	cancel()

	var exec models.PipelineExecution
	if err := s.db.First(&exec, "id = ?", executionID).Error; err != nil {
		return err
	}

	exec.Status = types.ExecutionStatusCancelled
	exec.CancelRequested = true
	exec.CanceledAt = timePtr(time.Now())
	s.db.Save(&exec)

	return nil
}

func (s *Scheduler) monitorTimeouts() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		now := time.Now()
		var execs []models.PipelineExecution
		s.db.Where("status = ? AND timeout_at < ?", types.ExecutionStatusRunning, now).Find(&execs)

		for _, exec := range execs {
			logger.Warn("execution timeout detected",
				zap.String("execution_id", string(exec.ID)))
			s.CancelExecution(exec.ID)

			exec.Status = types.ExecutionStatusTimeout
			s.db.Save(&exec)
		}
	}
}

func (s *Scheduler) cleanupExpired() {
	ticker := time.NewTicker(24 * time.Hour)
	defer ticker.Stop()

	for range ticker.C {
		if s.artifactMgr != nil {
			s.artifactMgr.CleanupExpired(context.Background())
		}
		if s.logStore != nil {
			s.logStore.CleanupExpired(context.Background())
		}
	}
}

func convertEnvVars(env []types.EnvVar) map[string]string {
	result := make(map[string]string)
	for _, e := range env {
		result[e.Name] = e.Value
	}
	return result
}

func mustJSON(v interface{}) datatypes.JSON {
	data, _ := json.Marshal(v)
	return datatypes.JSON(data)
}

func timePtr(t time.Time) *time.Time {
	return &t
}

func intPtr(i int) *int {
	return &i
}

func int64Ptr(i int64) *int64 {
	return &i
}

type logWriter struct {
	execID   types.ID
	stageID  types.ID
	stream   string
	logStore *logstore.LogStore
}

func (w *logWriter) Write(p []byte) (n int, err error) {
	msg := string(p)
	if len(msg) > 0 && msg[len(msg)-1] == '\n' {
		msg = msg[:len(msg)-1]
	}
	w.logStore.AppendLog(w.execID, w.stageID, "INFO", msg, w.stream)
	return len(p), nil
}

func createCommand(ctx context.Context, cmdStr, dir string, env []string) *exec.Cmd {
	cmd := exec.CommandContext(ctx, "sh", "-c", cmdStr)
	cmd.Dir = dir
	cmd.Env = env
	return cmd
}
