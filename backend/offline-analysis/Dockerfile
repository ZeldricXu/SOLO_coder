# Dockerfile for Offline Analysis Service (Python)
FROM python:3.11-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    libmariadb-dev \
    cron \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

RUN echo "0 2 * * * cd /app && python main.py daily_batch >> /var/log/cron.log 2>&1" > /etc/cron.d/analysis-cron
RUN chmod 0644 /etc/cron.d/analysis-cron
RUN crontab /etc/cron.d/analysis-cron

RUN touch /var/log/cron.log

CMD ["cron", "-f"]
