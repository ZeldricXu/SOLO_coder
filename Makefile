.PHONY: install dev test lint clean docker-build docker-up docker-down

install:
	pip install -r requirements.txt

dev:
	uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

test:
	pytest tests/ -v

test-cov:
	pytest tests/ -v --cov=app --cov-report=term --cov-report=html

lint:
	flake8 app/ tests/
	pylint app/
	mypy app/

format:
	black app/ tests/
	isort app/ tests/

clean:
	find . -type f -name "*.pyc" -delete
	find . -type d -name "__pycache__" -delete
	rm -rf .pytest_cache
	rm -rf htmlcov
	rm -rf .coverage
	rm -rf logs/*
	rm -rf storage/*

docker-build:
	docker-compose build

docker-up:
	docker-compose up -d

docker-down:
	docker-compose down

docker-logs:
	docker-compose logs -f

worker:
	celery -A app.tasks.worker worker --loglevel=info

beat:
	celery -A app.tasks.worker beat --loglevel=info

migrate:
	alembic upgrade head

migrate-create:
	alembic revision --autogenerate -m "$(m)"

rollback:
	alembic downgrade -1

scaffold:
	python -m app.cli scaffold fastapi ./generated
