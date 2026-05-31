package core

import "github.com/solocoder/task-scheduler/internal/contracts"

type ProcessRequest = contracts.ProcessRequest
type ProcessResult = contracts.ProcessResult
type ProcessingRules = contracts.ProcessingRules
type ValidationError = contracts.ValidationError
type TimeoutError = contracts.TimeoutError

type ConfigLoader = contracts.ConfigLoader
type ParameterValidator = contracts.ParameterValidator
type ResourcePool = contracts.ResourcePool
type MetricsCollector = contracts.MetricsCollector
type RunInstanceManager = contracts.RunInstanceManager
type ResultPersister = contracts.ResultPersister
type EventPublisher = contracts.EventPublisher
type TaskProcessor = contracts.TaskProcessor
type TaskExecutorInterface = contracts.TaskExecutor
type RuleExtractor = contracts.RuleExtractor
