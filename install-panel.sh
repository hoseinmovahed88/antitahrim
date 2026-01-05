#!/bin/bash

# 3X-UI Panel Installation Script
# Automated installation for web panel

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

clear
echo "=========================================="
echo "  3X-UI Panel - Auto Install"
echo "=========================================="
echo

print_info "Installing 3X-UI web panel..."
echo

# Run official installer
bash <(curl -Ls https://raw.githubusercontent.com/mhsanaei/3x-ui/master/install.sh)

# Open firewall port
print_info "Opening firewall port 2053..."
ufw allow 2053/tcp 2>/dev/null || true

SERVER_IP=$(curl -s4 ifconfig.me 2>/dev/null || echo "YOUR_SERVER_IP")

echo
echo "=========================================="
print_success "3X-UI Panel Installed!"
echo "=========================================="
echo
print_info "Access panel at:"
echo "  http://${SERVER_IP}:2053"
echo
print_info "Default credentials:"
echo "  Username: admin"
echo "  Password: admin"
echo
print_info "⚠️  IMPORTANT: Change username and password after first login!"
echo
print_info "Management commands:"
echo "  x-ui              - Management menu"
echo "  systemctl status x-ui"
echo "  systemctl restart x-ui"
echo
echo "=========================================="
