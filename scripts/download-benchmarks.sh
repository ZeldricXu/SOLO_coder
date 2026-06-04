#!/usr/bin/env bash
#
# download-benchmarks.sh - Download CFD benchmark meshes from S3/GCS
#
# Usage:
#   ./scripts/download-benchmarks.sh              # Download all benchmarks
#   ./scripts/download-benchmarks.sh cavity_128   # Download specific benchmark
#   ./scripts/download-benchmarks.sh --force      # Force re-download existing files
#   ./scripts/download-benchmarks.sh --check      # Just verify checksums
#
# Environment Variables:
#   BENCHMARK_BASE_URL: Override the default download base URL
#   BENCHMARK_DEST_DIR: Override the default destination directory

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

BENCHMARK_BASE_URL="${BENCHMARK_BASE_URL:-https://cfd-benchmarks.s3.us-west-2.amazonaws.com}"
BENCHMARK_DEST_DIR="${BENCHMARK_DEST_DIR:-${PROJECT_ROOT}/data/benchmarks}"

FORCE_DOWNLOAD=false
CHECK_ONLY=false
TARGET_BENCHMARK=""

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

error() {
    echo "ERROR: $*" >&2
    exit 1
}

verify_checksum() {
    local file="$1"
    local expected_checksum="$2"

    if [[ ! -f "$file" ]]; then
        return 1
    fi

    local actual_checksum=""
    if command -v sha256sum >/dev/null 2>&1; then
        actual_checksum="$(sha256sum "$file" | awk '{print $1}')"
    elif command -v shasum >/dev/null 2>&1; then
        actual_checksum="$(shasum -a 256 "$file" | awk '{print $1}')"
    else
        error "Neither sha256sum nor shasum found"
    fi

    [[ "$actual_checksum" == "$expected_checksum" ]]
}

download_file() {
    local url="$1"
    local dest="$2"
    local expected_checksum="$3"

    local dest_dir
    dest_dir="$(dirname "$dest")"
    mkdir -p "$dest_dir"

    if [[ -f "$dest" ]] && [[ "$FORCE_DOWNLOAD" == "false" ]]; then
        if verify_checksum "$dest" "$expected_checksum"; then
            log "  ✓ $dest (already exists, checksum OK)"
            return 0
        else
            log "  ⚠ $dest (checksum mismatch, re-downloading)"
        fi
    fi

    log "  ↓ Downloading $url"

    local download_cmd=""
    if command -v curl >/dev/null 2>&1; then
        download_cmd="curl -fSL --retry 3 --retry-delay 5 --connect-timeout 30"
    elif command -v wget >/dev/null 2>&1; then
        download_cmd="wget -q --tries=3 --waitretry=5 --timeout=30"
    else
        error "Neither curl nor wget found"
    fi

    if ! $download_cmd "$url" -o "${dest}.tmp"; then
        rm -f "${dest}.tmp"
        error "Failed to download $url"
    fi

    mv "${dest}.tmp" "$dest"

    if ! verify_checksum "$dest" "$expected_checksum"; then
        rm -f "$dest"
        error "Checksum mismatch for $dest"
    fi

    log "  ✓ $dest (download complete)"
}

declare -A BENCHMARK_FILES=(
    ["cavity_128.msh"]="d4a5b3e6f7a8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4"
    ["cavity_256.msh"]="e5b6c7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"
    ["backward_step_3d.cgns"]="f6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7"
    ["cylinder_2d.polymesh.tar.gz"]="a7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8"
    ["cylinder_3d.cgns"]="b8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9"
)

declare -A BENCHMARK_GROUPS=(
    ["cavity_128"]="cavity_128.msh"
    ["cavity_256"]="cavity_256.msh"
    ["backward_step"]="backward_step_3d.cgns"
    ["cylinder_2d"]="cylinder_2d.polymesh.tar.gz"
    ["cylinder_3d"]="cylinder_3d.cgns"
)

usage() {
    cat <<EOF
Usage: $0 [OPTIONS] [BENCHMARK]

Download CFD benchmark mesh files from S3.

Options:
  --force       Force re-download even if files exist
  --check       Only verify checksums of existing files
  --help        Show this help message

Available benchmarks:
  cavity_128      2D lid-driven cavity, 128x128 quad mesh (Gmsh)
  cavity_256      2D lid-driven cavity, 256x256 quad mesh (Gmsh)
  backward_step   3D backward-facing step (CGNS)
  cylinder_2d     2D flow over cylinder (OpenFOAM polyMesh)
  cylinder_3d     3D flow over cylinder (CGNS)

Environment Variables:
  BENCHMARK_BASE_URL    Base URL for downloads (default: S3 bucket)
  BENCHMARK_DEST_DIR    Destination directory (default: data/benchmarks)
EOF
    exit 0
}

for arg in "$@"; do
    case "$arg" in
        --force)
            FORCE_DOWNLOAD=true
            shift
            ;;
        --check)
            CHECK_ONLY=true
            shift
            ;;
        --help|-h)
            usage
            ;;
        *)
            if [[ -z "$TARGET_BENCHMARK" ]]; then
                TARGET_BENCHMARK="$arg"
                shift
            else
                error "Unexpected argument: $arg"
            fi
            ;;
    esac
done

mkdir -p "${BENCHMARK_DEST_DIR}"
log "Destination: ${BENCHMARK_DEST_DIR}"
log "Base URL: ${BENCHMARK_BASE_URL}"

files_to_download=()
if [[ -n "$TARGET_BENCHMARK" ]]; then
    if [[ -v "BENCHMARK_GROUPS[$TARGET_BENCHMARK]" ]]; then
        files_to_download+=("${BENCHMARK_GROUPS[$TARGET_BENCHMARK]}")
    elif [[ -v "BENCHMARK_FILES[$TARGET_BENCHMARK]" ]]; then
        files_to_download+=("$TARGET_BENCHMARK")
    else
        error "Unknown benchmark: $TARGET_BENCHMARK. Use --help for available options."
    fi
else
    for file in "${!BENCHMARK_FILES[@]}"; do
        files_to_download+=("$file")
    done
fi

total_files="${#files_to_download[@]}"
log "Processing ${total_files} file(s)..."

if [[ "$CHECK_ONLY" == "true" ]]; then
    log "Check-only mode (no downloads)"
    all_ok=true
    for file in "${files_to_download[@]}"; do
        checksum="${BENCHMARK_FILES[$file]}"
        dest="${BENCHMARK_DEST_DIR}/${file}"
        if verify_checksum "$dest" "$checksum"; then
            log "  ✓ $file (checksum OK)"
        else
            log "  ✗ $file (missing or checksum mismatch)"
            all_ok=false
        fi
    done
    if [[ "$all_ok" == "false" ]]; then
        exit 1
    fi
    log "All checksums verified successfully"
    exit 0
fi

for file in "${files_to_download[@]}"; do
    if [[ ! -v "BENCHMARK_FILES[$file]" ]]; then
        error "No checksum defined for $file"
    fi

    checksum="${BENCHMARK_FILES[$file]}"
    url="${BENCHMARK_BASE_URL}/${file}"
    dest="${BENCHMARK_DEST_DIR}/${file}"

    download_file "$url" "$dest" "$checksum"

    if [[ "$file" == *".tar.gz" ]] || [[ "$file" == *".tar" ]]; then
        extract_dir="${BENCHMARK_DEST_DIR}/$(basename "$file" .tar.gz)"
        extract_dir="${extract_dir%.tar}"
        log "  Extracting ${file} to ${extract_dir}"
        mkdir -p "$extract_dir"
        if [[ "$file" == *".tar.gz" ]]; then
            tar -xzf "$dest" -C "$extract_dir"
        else
            tar -xf "$dest" -C "$extract_dir"
        fi
        log "  ✓ Extraction complete"
    fi
done

cat > "${BENCHMARK_DEST_DIR}/README.md" <<EOF
# CFD Benchmark Datasets

Downloaded on $(date '+%Y-%m-%d %H:%M:%S')

All files are in the public domain for benchmarking purposes.

## Available Meshes

| File | Format | Description |
|------|--------|-------------|
| cavity_128.msh | Gmsh ASCII | 2D lid-driven cavity, 128x128 quads |
| cavity_256.msh | Gmsh ASCII | 2D lid-driven cavity, 256x256 quads |
| backward_step_3d.cgns | CGNS (HDF5) | 3D backward-facing step, hexahedral mesh |
| cylinder_2d/ | OpenFOAM polyMesh | 2D flow over circular cylinder |
| cylinder_3d.cgns | CGNS (HDF5) | 3D flow over circular cylinder |
EOF

log ""
log "All benchmarks downloaded successfully!"
log "To run a benchmark: cargo bench --bench cfd_benchmarks"
