#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() {
    echo "Usage: $0 [OPTIONS] ENVIRONMENT"
    echo ""
    echo "Deploy logrotate application to Kubernetes"
    echo ""
    echo "Arguments:"
    echo "  ENVIRONMENT   Environment to deploy (dev/staging/production)"
    echo ""
    echo "Options:"
    echo "  -c, --chart PATH       Path to Helm chart (default: ./deploy/helm/logrotate)"
    echo "  -n, --namespace NAME   Kubernetes namespace (default: logrotate)"
    echo "  -r, --registry URL     Docker registry URL"
    echo "  -t, --tag TAG          Docker image tag (default: latest)"
    echo "  -f, --values FILE      Additional values file"
    echo "  --dry-run              Dry run mode"
    echo "  --debug                Enable debug output"
    echo "  -h, --help             Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 staging"
    echo "  $0 production --tag v1.0.0"
    echo "  $0 dev --dry-run"
}

log() {
    echo "[$(date +'%Y-%m-%dT%H:%M:%S%z')] $*"
}

error() {
    log "ERROR: $*" >&2
    exit 1
}

# Default values
CHART_PATH="${PROJECT_ROOT}/deploy/helm/logrotate"
NAMESPACE="logrotate"
REGISTRY=""
TAG="latest"
VALUES_FILE=""
DRY_RUN=false
DEBUG=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -c|--chart)
            CHART_PATH="$2"
            shift 2
            ;;
        -n|--namespace)
            NAMESPACE="$2"
            shift 2
            ;;
        -r|--registry)
            REGISTRY="$2"
            shift 2
            ;;
        -t|--tag)
            TAG="$2"
            shift 2
            ;;
        -f|--values)
            VALUES_FILE="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --debug)
            DEBUG=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        -*)
            error "Unknown option: $1"
            ;;
        *)
            if [[ -z "${ENVIRONMENT:-}" ]]; then
                ENVIRONMENT="$1"
            else
                error "Unexpected argument: $1"
            fi
            shift
            ;;
    esac
done

# Validate environment
if [[ -z "${ENVIRONMENT:-}" ]]; then
    error "Environment argument is required"
fi

case "$ENVIRONMENT" in
    dev|staging|production)
        ;;
    *)
        error "Invalid environment: $ENVIRONMENT (must be dev, staging, or production)"
        ;;
esac

# Check prerequisites
command -v kubectl >/dev/null 2>&1 || error "kubectl is not installed"
command -v helm >/dev/null 2>&1 || error "helm is not installed"

# Check Kubernetes connection
if ! kubectl cluster-info >/dev/null 2>&1; then
    error "Cannot connect to Kubernetes cluster"
fi

log "Starting deployment to $ENVIRONMENT environment"

# Build helm arguments
HELM_ARGS=(
    "upgrade"
    "--install"
    "logrotate"
    "$CHART_PATH"
    "--namespace"
    "$NAMESPACE"
    "--create-namespace"
    "--values"
    "${CHART_PATH}/values.${ENVIRONMENT}.yaml"
    "--set"
    "image.tag=${TAG}"
    "--wait"
    "--timeout"
    "10m"
)

if [[ -n "$REGISTRY" ]]; then
    HELM_ARGS+=("--set" "image.repository=${REGISTRY}/logrotate")
fi

if [[ -n "$VALUES_FILE" ]]; then
    HELM_ARGS+=("--values" "$VALUES_FILE")
fi

if [[ "$DRY_RUN" == true ]]; then
    HELM_ARGS+=("--dry-run")
fi

if [[ "$DEBUG" == true ]]; then
    HELM_ARGS+=("--debug")
fi

log "Running helm command: helm ${HELM_ARGS[*]}"

if helm "${HELM_ARGS[@]}"; then
    log "Deployment completed successfully"
else
    error "Deployment failed"
fi

# Verify deployment
log "Verifying deployment..."
if ! kubectl rollout status deployment/logrotate --namespace "$NAMESPACE" --timeout=5m; then
    error "Deployment verification failed"
fi

log "Deployment verification passed"

# Show deployment info
log "Deployment information:"
kubectl get pods --namespace "$NAMESPACE" -l app.kubernetes.io/name=logrotate
kubectl get services --namespace "$NAMESPACE" -l app.kubernetes.io/name=logrotate
kubectl get ingress --namespace "$NAMESPACE" -l app.kubernetes.io/name=logrotate

log "Deployment completed successfully!"
