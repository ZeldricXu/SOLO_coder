# Structured Logging Platform

A lightweight and efficient platform for structured logging, feature management, GPU task scheduling, and more.

## Features

### Core Modules

- **Feature Store Service**: Feature registration, online serving, and offline backtracking with online-offline consistency guarantees
- **Structured Logging**: JSON-formatted logs with context propagation
- **Monitoring & Analytics**: Performance metrics exposure and querying with Prometheus integration
- **GPU Task Scheduler**: Fine-grained GPU resource allocation, priority queues, and preemption
- **Storage Management**: Object storage adapter with metadata indexing
- **Data Access Layer**: Data migration and schema version control
- **API Gateway**: Authentication, authorization, and rate limiting
- **Prompt Experiment Management**: Prompt version control, A/B testing, and evaluation
- **Core Processing**: Task scheduling and execution management

## Tech Stack

- **Language**: Python 3.11+
- **Web Framework**: FastAPI with Pydantic
- **ORM**: SQLAlchemy 2.0 with Alembic migrations
- **Async Support**: Fully async/await
- **Database**: PostgreSQL (asyncpg)
- **Caching/Message Broker**: Redis
- **Testing**: pytest with pytest-asyncio
- **Metrics**: Prometheus

## Quick Start

### 1. Install Dependencies

```bash
pip install -r requirements.txt
```

### 2. Configure Environment

```bash
cp .env.example .env
# Edit .env with your configuration
```

### 3. Initialize Database

```bash
alembic upgrade head
```

### 4. Start the Server

```bash
python -m app.main
```

The API will be available at `http://localhost:8000`

- API Documentation: `http://localhost:8000/docs`
- Health Check: `http://localhost:8000/health`

## Project Structure

```
session177/
├── app/
│   ├── __init__.py
│   ├── main.py              # FastAPI application entry point
│   ├── config/              # Configuration management
│   ├── logging/             # Structured logging module
│   ├── models/              # SQLAlchemy ORM models
│   ├── schemas/             # Pydantic request/response schemas
│   ├── database.py          # Database connection
│   ├── exceptions.py        # Custom exceptions
│   ├── utils.py             # Utility functions
│   ├── feature_store/       # Feature store service
│   ├── monitoring/          # Monitoring and metrics
│   ├── gpu_scheduler/       # GPU task scheduling
│   ├── storage/             # Object storage management
│   ├── data_access/         # Data access and migration
│   ├── api_gateway/         # Auth and rate limiting
│   ├── prompt_experiment/   # Prompt A/B testing
│   └── core/                # Core task execution
├── alembic/                 # Database migrations
├── tests/                   # Test suite
├── alembic.ini              # Alembic configuration
├── pytest.ini               # pytest configuration
├── conftest.py              # pytest fixtures
├── requirements.txt         # Python dependencies
└── .env.example             # Environment variables template
```

## API Endpoints

### Authentication
- `POST /api/v1/auth/register` - Register new user
- `POST /api/v1/auth/login` - Login and get access token
- `POST /api/v1/auth/api-key` - Generate API key

### Feature Store
- `POST /api/v1/features` - Register feature
- `GET /api/v1/features` - List features
- `GET /api/v1/features/{id}` - Get feature details
- `PUT /api/v1/features/{id}` - Update feature
- `DELETE /api/v1/features/{id}` - Delete feature
- `POST /api/v1/features/online` - Get online features
- `POST /api/v1/features/offline` - Get offline features
- `POST /api/v1/features/check-consistency` - Check online/offline consistency

### Monitoring
- `GET /api/v1/monitoring/metrics` - Get current metrics
- `POST /api/v1/monitoring/snapshots` - Create metric snapshot
- `GET /api/v1/monitoring/snapshots` - List snapshots
- `GET /api/v1/monitoring/audit-logs` - Get audit logs

### GPU Scheduler
- `POST /api/v1/gpu/tasks` - Submit GPU task
- `GET /api/v1/gpu/tasks` - List tasks
- `GET /api/v1/gpu/tasks/{id}` - Get task status
- `DELETE /api/v1/gpu/tasks/{id}` - Cancel task
- `GET /api/v1/gpu/resources` - List GPU resources
- `POST /api/v1/gpu/resources` - Register GPU resource

### Storage
- `POST /api/v1/storage/objects` - Upload object
- `GET /api/v1/storage/objects/{bucket}/{key}` - Download object
- `DELETE /api/v1/storage/objects/{bucket}/{key}` - Delete object
- `GET /api/v1/storage/objects` - List objects

### Data Access
- `POST /api/v1/data/schemas` - Register schema version
- `GET /api/v1/data/schemas` - List schemas
- `POST /api/v1/data/migrations` - Create data migration
- `GET /api/v1/data/migrations` - List migrations

### Prompt Experiments
- `POST /api/v1/prompts` - Create prompt version
- `GET /api/v1/prompts` - List prompts
- `POST /api/v1/prompts/render` - Render prompt
- `POST /api/v1/ab-tests` - Create A/B test
- `GET /api/v1/ab-tests` - List A/B tests
- `POST /api/v1/ab-tests/{id}/analyze` - Analyze test results
- `POST /api/v1/experiments` - Create experiment
- `GET /api/v1/experiments` - List experiments

### Core Processing
- `POST /api/v1/core/resources` - Create resource
- `GET /api/v1/core/resources/{id}/status` - Get resource status
- `POST /api/v1/core/resources/batch` - Batch operations
- `POST /api/v1/core/tasks/execute` - Execute task
- `GET /api/v1/core/tasks/{id}/result` - Get task result
- `GET /api/v1/core/tasks` - List tasks

## Testing

```bash
# Run all tests
pytest

# Run specific test file
pytest tests/test_logging.py

# Run with coverage
pytest --cov=app --cov-report=html
```

## License

MIT
