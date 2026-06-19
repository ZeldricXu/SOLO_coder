#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/../../pkg/grpcapi"

PROTO_FILES=(
  "parameter.proto"
  "objective.proto"
  "task.proto"
  "compute.proto"
  "worker.proto"
)

GO_PACKAGE_BASE="github.com/lab/distcomp/pkg/grpcapi"

check_dependencies() {
  local missing=()

  if ! command -v protoc &> /dev/null; then
    missing+=("protoc (protobuf compiler)")
  fi

  if ! command -v protoc-gen-go &> /dev/null; then
    missing+=("protoc-gen-go")
  fi

  if ! command -v protoc-gen-go-grpc &> /dev/null; then
    missing+=("protoc-gen-go-grpc")
  fi

  if [ ${#missing[@]} -gt 0 ]; then
    echo "Error: Missing required dependencies:"
    for dep in "${missing[@]}"; do
      echo "  - $dep"
    done
    echo ""
    echo "To install, run:"
    echo "  brew install protobuf"
    echo "  go install google.golang.org/protobuf/cmd/protoc-gen-go@latest"
    echo "  go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest"
    echo "  export PATH=\"\$PATH:$(go env GOPATH)/bin\""
    exit 1
  fi
}

generate_code() {
  echo "Generating gRPC code..."
  echo "Input directory: ${SCRIPT_DIR}"
  echo "Output directory: ${OUTPUT_DIR}"
  echo ""

  mkdir -p "${OUTPUT_DIR}"

  for proto_file in "${PROTO_FILES[@]}"; do
    if [ -f "${SCRIPT_DIR}/${proto_file}" ]; then
      echo "Processing ${proto_file}..."

      protoc \
        --proto_path="${SCRIPT_DIR}" \
        --go_out="${OUTPUT_DIR}" \
        --go_opt=module="${GO_PACKAGE_BASE}" \
        --go-grpc_out="${OUTPUT_DIR}" \
        --go-grpc_opt=module="${GO_PACKAGE_BASE}" \
        "${SCRIPT_DIR}/${proto_file}"

      echo "  ✓ Generated code for ${proto_file}"
    else
      echo "  ✗ Skipping ${proto_file}: File not found"
    fi
  done

  echo ""
  echo "Generation complete!"
  echo "Output files are in: ${OUTPUT_DIR}"
}

show_help() {
  cat << EOF
Usage: $(basename "$0") [OPTIONS]

Generate gRPC Go code from protobuf definitions.

Options:
  -h, --help    Show this help message and exit
  -c, --check   Check dependencies and exit
  -o, --output  Specify output directory (default: ../../pkg/grpcapi)

Examples:
  $(basename "$0")
  $(basename "$0") --output /path/to/output
  $(basename "$0") --check
EOF
}

main() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -h|--help)
        show_help
        exit 0
        ;;
      -c|--check)
        check_dependencies
        echo "All dependencies are installed."
        exit 0
        ;;
      -o|--output)
        OUTPUT_DIR="$2"
        shift 2
        ;;
      *)
        echo "Unknown option: $1"
        show_help
        exit 1
        ;;
    esac
  done

  check_dependencies
  generate_code
}

main "$@"
