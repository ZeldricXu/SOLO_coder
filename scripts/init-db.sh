#!/bin/bash
set -e

# 检查是否已创建数据库
if [ -z "$(psql -U postgres -tAc "SELECT 1 FROM pg_database WHERE datname='ticket_routing_dev'")" ]; then
    echo "Creating database: ticket_routing_dev"
    psql -U postgres -c "CREATE DATABASE ticket_routing_dev;"
fi

# 检查是否已创建测试数据库
if [ -z "$(psql -U postgres -tAc "SELECT 1 FROM pg_database WHERE datname='ticket_routing_test'")" ]; then
    echo "Creating database: ticket_routing_test"
    psql -U postgres -c "CREATE DATABASE ticket_routing_test;"
fi

# 创建扩展
psql -U postgres -d ticket_routing_dev -c "CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\";"
psql -U postgres -d ticket_routing_test -c "CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\";"

echo "Database initialization completed successfully"
