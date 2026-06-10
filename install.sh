#!/usr/bin/env bash
#
# gitflow CLI Install Script
#
# This script installs gitflow on macOS and Linux systems.
# It detects the platform and architecture, downloads the appropriate binary,
# verifies the SHA256 checksum, and installs it to /usr/local/bin.
#
# Usage:
#   ./install.sh [version]
#   curl -fsSL https://raw.githubusercontent.com/your-org/gitflow/main/install.sh | bash
#   curl -fsSL https://raw.githubusercontent.com/your-org/gitflow/main/install.sh | bash -s -- v0.1.0
#

set -euo pipefail

# Configuration
BINARY_NAME="gitflow"
REPO_OWNER="your-org"       # Update this to your GitHub org/username
REPO_NAME="gitflow-cli"     # Update this to your repo name
INSTALL_DIR="/usr/local/bin"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Helper functions
info() {
    echo -e "${BLUE}ℹ${NC}  $1"
}

success() {
    echo -e "${GREEN}✓${NC}  $1"
}

warning() {
    echo -e "${YELLOW}⚠${NC}  $1"
}

error() {
    echo -e "${RED}✗${NC}  $1" >&2
    exit 1
}

# Parse arguments
VERSION="${1:-latest}"

# Validate version format if not "latest"
if [ "$VERSION" != "latest" ] && ! [[ "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    error "Invalid version format: $VERSION. Use 'latest' or 'vX.Y.Z'"
fi

# Detect OS
OS="$(uname -s)"
case "$OS" in
    Darwin*)
        OS="macos"
        ;;
    Linux*)
        OS="linux"
        ;;
    MINGW*|MSYS*|CYGWIN*)
        error "Windows is not supported by this script. Please download manually from GitHub Releases."
        ;;
    *)
        error "Unsupported operating system: $OS"
        ;;
esac

# Detect architecture
ARCH="$(uname -m)"
case "$ARCH" in
    x86_64|amd64)
        ARCH="x86_64"
        ;;
    aarch64|arm64)
        ARCH="aarch64"
        ;;
    armv7l|armv6l)
        error "ARMv7/ARMv6 is not supported. Please use a device with ARM64 or x86_64."
        ;;
    i386|i686)
        error "32-bit x86 is not supported."
        ;;
    *)
        error "Unsupported architecture: $ARCH"
        ;;
esac

# Display detected platform
info "Detected platform: ${OS} (${ARCH})"

# Get latest version if needed
if [ "$VERSION" = "latest" ]; then
    info "Fetching latest version..."
    VERSION=$(curl -fsSL "https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/releases/latest" | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/')
    if [ -z "$VERSION" ]; then
        error "Failed to fetch latest version. Please check your internet connection or specify a version explicitly."
    fi
    info "Latest version is ${VERSION}"
fi

# Remove 'v' prefix for version number (for filename)
VERSION_NO_V="${VERSION#v}"

# Construct download URLs
ASSET_NAME="${BINARY_NAME}-${VERSION_NO_V}-${OS}-${ARCH}.tar.gz"
DOWNLOAD_URL="https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/download/${VERSION}/${ASSET_NAME}"
CHECKSUM_URL="https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/download/${VERSION}/SHA256SUMS"

# Create temporary directory
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# Download binary
info "Downloading ${ASSET_NAME}..."
curl -fsSL "$DOWNLOAD_URL" -o "${TMP_DIR}/${ASSET_NAME}" || {
    error "Failed to download ${ASSET_NAME}. Please check that the version exists."
}

# Download SHA256 checksums
info "Downloading SHA256 checksums..."
curl -fsSL "$CHECKSUM_URL" -o "${TMP_DIR}/SHA256SUMS" || {
    warning "Failed to download SHA256SUMS. Skipping verification."
    SKIP_CHECKSUM=1
}

# Verify SHA256 checksum
if [ -z "${SKIP_CHECKSUM:-}" ]; then
    info "Verifying SHA256 checksum..."
    cd "$TMP_DIR"
    EXPECTED_SHA=$(grep "${ASSET_NAME}" SHA256SUMS | awk '{print $1}')
    ACTUAL_SHA=$(sha256sum "${ASSET_NAME}" | awk '{print $1}')

    if [ -z "$EXPECTED_SHA" ]; then
        warning "No checksum found for ${ASSET_NAME}. Skipping verification."
    elif [ "$EXPECTED_SHA" != "$ACTUAL_SHA" ]; then
        error "SHA256 checksum mismatch! Expected: ${EXPECTED_SHA}, Got: ${ACTUAL_SHA}. Download may be corrupted."
    else
        success "SHA256 checksum verified successfully"
    fi
    cd - > /dev/null
fi

# Extract binary
info "Extracting binary..."
tar -xzf "${TMP_DIR}/${ASSET_NAME}" -C "$TMP_DIR"

# Verify binary exists
if [ ! -f "${TMP_DIR}/${BINARY_NAME}" ]; then
    error "Binary not found in archive. The download may be corrupted."
fi

# Make binary executable
chmod +x "${TMP_DIR}/${BINARY_NAME}"

# Test binary
info "Testing binary..."
"${TMP_DIR}/${BINARY_NAME}" --version > /dev/null || {
    error "Binary failed to execute. Please check if your system is compatible."
}

# Determine install directory
if [ ! -w "$INSTALL_DIR" ]; then
    warning "$INSTALL_DIR is not writable. Will use sudo to install."
    USE_SUDO=1
fi

# Install binary
info "Installing ${BINARY_NAME} to ${INSTALL_DIR}..."
if [ -n "${USE_SUDO:-}" ]; then
    sudo cp "${TMP_DIR}/${BINARY_NAME}" "${INSTALL_DIR}/${BINARY_NAME}"
    sudo chmod +x "${INSTALL_DIR}/${BINARY_NAME}"
else
    cp "${TMP_DIR}/${BINARY_NAME}" "${INSTALL_DIR}/${BINARY_NAME}"
    chmod +x "${INSTALL_DIR}/${BINARY_NAME}"
fi

# Verify installation
info "Verifying installation..."
INSTALLED_VERSION="$(${INSTALL_DIR}/${BINARY_NAME} --version | awk '{print $2}')"
EXPECTED_INSTALLED_VERSION="${VERSION#v}"

if [ "$INSTALLED_VERSION" != "$EXPECTED_INSTALLED_VERSION" ]; then
    warning "Installed version (${INSTALLED_VERSION}) does not match expected version (${EXPECTED_INSTALLED_VERSION})."
else
    success "Successfully installed ${BINARY_NAME} ${VERSION}"
fi

# Display post-installation instructions
echo ""
echo -e "${GREEN}${BINARY_NAME} has been successfully installed!${NC}"
echo ""
echo "To get started, run:"
echo "  ${BINARY_NAME} --help"
echo ""
echo "To initialize configuration, run:"
echo "  ${BINARY_NAME} config init"
echo ""
echo "For more information, visit: https://github.com/${REPO_OWNER}/${REPO_NAME}"
echo ""

# Check if install directory is in PATH
if ! echo "$PATH" | grep -q "${INSTALL_DIR}"; then
    warning "${INSTALL_DIR} is not in your PATH. You may need to add it."
    echo "  Add this to your ~/.bashrc, ~/.zshrc, or equivalent:"
    echo "  export PATH=\"${INSTALL_DIR}:\$PATH\""
fi
