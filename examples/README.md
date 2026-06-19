# Examples

This directory contains example code demonstrating how to use the DF1-96 experiment platform.

## Prerequisites

1. Go 1.24 or later
2. Access to a running scheduler (default: localhost:50051)
3. Required dependencies installed

## Running Examples

### 1. Client Example (`client/client.go`)

Demonstrates basic client operations:
- Creating and submitting tasks
- Querying task status
- Listing tasks
- Evaluating objective functions
- Streaming task status updates
- Cancelling tasks

```bash
cd examples/client
go run client.go
```

### 2. Parameter Scan Example (`parameter_scan/parameter_scan.go`)

Demonstrates running a parameter sweep over multiple hyperparameters:
- Generating parameter combinations
- Concurrent task execution
- Collecting and analyzing results
- Finding the optimal parameter configuration

```bash
cd examples/parameter_scan
go run parameter_scan.go
```

### 3. Custom Objective Example (`custom_objective/custom_objective.go`)

Demonstrates implementing and using custom objective functions:
- Creating a custom objective function implementing the `TestFunction` interface
- Testing function evaluation and gradient computation
- Comparing analytical gradients with numerical gradients
- Running optimization with custom objectives
- Integrating with the compute engine

```bash
cd examples/custom_objective
go run custom_objective.go
```

## Example Details

### Client Example Workflow

1. **Connect** to the scheduler gRPC server
2. **Create** a new optimization task
3. **Query** the task status
4. **List** all tasks in the experiment
5. **Evaluate** an objective function directly
6. **Monitor** task status via streaming
7. **Cancel** the task

### Parameter Scan Example

This example performs a grid search over:
- Learning rate: [0.001, 0.01, 0.1, 0.5, 1.0]
- Beta1 (Adam optimizer): [0.8, 0.85, 0.9, 0.95, 0.99]

Total: 25 parameter combinations, executed with configurable concurrency.

### Custom Objective Example

Implements a custom wave function:

```
f(x) = distance(x, center) + amplitude * sin(avg(sin(frequency * (x_i - center_i))))
```

Features demonstrated:
- Function evaluation
- Analytical gradient computation
- Numerical gradient verification
- Optimization with Adam
- Integration with compute engine

## API Reference

### TaskClient Operations

- `CreateTask()` - Submit a new task
- `GetTask()` - Get task details by ID
- `ListTasks()` - List tasks with pagination
- `UpdateTaskStatus()` - Update task status
- `CancelTask()` - Cancel a running task
- `StreamTaskStatus()` - Stream real-time task updates

### ComputeClient Operations

- `Evaluate()` - Evaluate an objective function
- `Gradient()` - Compute function gradient
- `Optimize()` - Run optimization

### Plugin System

For extending the platform with custom objective functions as plugins,
see the `plugins/` directory. Plugins can be compiled as `.so` files
and loaded dynamically at runtime.

## Configuration

All examples connect to `localhost:50051` by default. To change the
scheduler address, modify the `grpcConfig.Address` in each example:

```go
grpcConfig := client.DefaultGRPCClientConfig()
grpcConfig.Address = "your-scheduler:50051"
```

## Error Handling

Each example includes basic error handling. In production code, you should:
- Implement proper retry logic for transient failures
- Handle authentication and authorization
- Add circuit breakers for downstream failures
- Implement proper logging and monitoring

## See Also

- [API Documentation](../../pkg/grpcapi/client/)
- [Plugin System](../../plugins/)
- [Configuration](../../configs/)
