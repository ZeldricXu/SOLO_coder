#!/bin/bash

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

echo "Generating gRPC Python code from proto files..."

python -m grpc_tools.protoc \
    -I./proto \
    --python_out=./app/grpc_api \
    --grpc_python_out=./app/grpc_api \
    ./proto/inventory.proto

echo "Generated files:"
ls -la app/grpc_api/inventory_pb2.py
ls -la app/grpc_api/inventory_pb2_grpc.py

echo "Fixing import paths..."
sed -i 's/import inventory_pb2 as inventory__pb2/from . import inventory_pb2 as inventory__pb2/' app/grpc_api/inventory_pb2_grpc.py

echo "gRPC code generation completed successfully!"
