FROM python:3.12-slim AS builder

WORKDIR /app

RUN pip install --no-cache-dir poetry>=1.7

COPY pyproject.toml poetry.lock* ./

RUN poetry config virtualenvs.create false \
    && poetry install --no-dev --no-interaction --no-ansi

FROM python:3.12-slim

WORKDIR /app

RUN groupadd -r dungeon && useradd -r -g dungeon -d /app dungeon

COPY --from=builder /usr/local/lib/python3.12/site-packages /usr/local/lib/python3.12/site-packages
COPY --from=builder /usr/local/bin /usr/local/bin

COPY server/ server/
COPY data/ data/
COPY server/__main__.py server/__main__.py
COPY server/app.py server/app.py
COPY server/config.py server/config.py

RUN mkdir -p /app/data && chown -R dungeon:dungeon /app

USER dungeon

EXPOSE 8765

ENV DUNGEON_ENVIRONMENT=production
ENV DUNGEON_HOST=0.0.0.0
ENV DUNGEON_PORT=8765

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import socket; s=socket.socket(); s.settimeout(2); s.connect(('localhost',8765)); s.close()" || exit 1

ENTRYPOINT ["python", "-m", "server"]
