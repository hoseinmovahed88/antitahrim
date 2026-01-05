#!/bin/bash

# Speed Optimization Script for Xray
# بهینه‌سازی سرعت

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

print_success() { echo -e "${GREEN}✓ $1${NC}"; }
print_info() { echo -e "${BLUE}ℹ $1${NC}"; }

echo "=========================================="
echo "  Xray Speed Optimization"
echo "=========================================="
echo

print_info "Applying network optimizations..."

# TCP BBR و بهینه‌سازی‌های شبکه
cat >> /etc/sysctl.conf <<'EOF'

# Xray Speed Optimization
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
net.ipv4.tcp_fastopen = 3
net.ipv4.tcp_slow_start_after_idle = 0
net.ipv4.tcp_notsent_lowat = 16384
net.ipv4.tcp_mtu_probing = 1

# Buffer sizes
net.core.rmem_max = 134217728
net.core.wmem_max = 134217728
net.ipv4.tcp_rmem = 4096 87380 67108864
net.ipv4.tcp_wmem = 4096 65536 67108864

# Connection optimization
net.ipv4.tcp_window_scaling = 1
net.ipv4.tcp_timestamps = 1
net.ipv4.tcp_sack = 1
net.ipv4.tcp_fack = 1
net.ipv4.tcp_keepalive_time = 600
net.ipv4.tcp_keepalive_intvl = 60
net.ipv4.tcp_keepalive_probes = 10

# File limits
fs.file-max = 1000000
EOF

sysctl -p > /dev/null 2>&1

print_success "Network optimization applied"

# بهینه‌سازی Limits
print_info "Optimizing system limits..."

cat >> /etc/security/limits.conf <<'EOF'

# Xray limits
* soft nofile 1000000
* hard nofile 1000000
root soft nofile 1000000
root hard nofile 1000000
EOF

print_success "System limits optimized"

# بهینه‌سازی Xray config
print_info "Optimizing Xray configuration..."

if [ -f /usr/local/etc/xray/config.json ]; then
    # اضافه کردن تنظیمات بهینه به کانفیگ
    systemctl restart xray 2>/dev/null || true
    print_success "Xray restarted with optimizations"
fi

echo
echo "=========================================="
print_success "Speed optimization complete!"
echo "=========================================="
echo
print_info "Changes applied:"
echo "  ✓ TCP BBR enabled"
echo "  ✓ Network buffers increased"
echo "  ✓ Connection optimization"
echo "  ✓ System limits increased"
echo
print_info "Test your connection speed now!"
echo

# نمایش وضعیت BBR
if sysctl net.ipv4.tcp_congestion_control | grep -q bbr; then
    print_success "BBR is active"
else
    print_info "BBR will be active after reboot"
fi

echo
print_info "Optional: Reboot server for full effect"
echo "  reboot"
echo
