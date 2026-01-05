#!/bin/bash

# Server Hardening & Optimization Script
# اسکریپت بهینه‌سازی و امن‌سازی سرور

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

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

check_root() {
    if [[ $EUID -ne 0 ]]; then
        print_error "این اسکریپت باید با دسترسی root اجرا شود."
        exit 1
    fi
}

# بهینه‌سازی شبکه برای سرعت بالا
optimize_network() {
    print_info "در حال بهینه‌سازی تنظیمات شبکه..."
    
    cat > /etc/sysctl.d/99-xray-optimization.conf <<EOF
# TCP BBR Congestion Control
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr

# TCP Fast Open
net.ipv4.tcp_fastopen = 3

# TCP Buffer Sizes
net.core.rmem_max = 134217728
net.core.wmem_max = 134217728
net.ipv4.tcp_rmem = 4096 87380 67108864
net.ipv4.tcp_wmem = 4096 65536 67108864

# TCP Keepalive
net.ipv4.tcp_keepalive_time = 600
net.ipv4.tcp_keepalive_intvl = 60
net.ipv4.tcp_keepalive_probes = 10

# Connection Tracking
net.netfilter.nf_conntrack_max = 1000000
net.netfilter.nf_conntrack_tcp_timeout_established = 7200

# IP Forward (برای Routing)
net.ipv4.ip_forward = 1
net.ipv6.conf.all.forwarding = 1

# Security
net.ipv4.conf.all.accept_redirects = 0
net.ipv4.conf.all.send_redirects = 0
net.ipv4.conf.all.accept_source_route = 0
net.ipv4.icmp_echo_ignore_broadcasts = 1
net.ipv4.icmp_ignore_bogus_error_responses = 1

# Performance
net.ipv4.tcp_window_scaling = 1
net.ipv4.tcp_timestamps = 1
net.ipv4.tcp_sack = 1
net.ipv4.tcp_no_metrics_save = 1
net.ipv4.tcp_moderate_rcvbuf = 1

# File Descriptor Limits
fs.file-max = 1000000
fs.inotify.max_user_instances = 8192
EOF
    
    sysctl -p /etc/sysctl.d/99-xray-optimization.conf > /dev/null 2>&1
    
    print_success "تنظیمات شبکه بهینه شدند"
}

# بهینه‌سازی Limits
optimize_limits() {
    print_info "در حال بهینه‌سازی محدودیت‌های سیستم..."
    
    cat >> /etc/security/limits.conf <<EOF

# Xray Performance Tuning
* soft nofile 1000000
* hard nofile 1000000
* soft nproc 1000000
* hard nproc 1000000
root soft nofile 1000000
root hard nofile 1000000
root soft nproc 1000000
root hard nproc 1000000
EOF
    
    # برای systemd
    mkdir -p /etc/systemd/system.conf.d
    cat > /etc/systemd/system.conf.d/limits.conf <<EOF
[Manager]
DefaultLimitNOFILE=1000000
DefaultLimitNPROC=1000000
EOF
    
    systemctl daemon-reload
    
    print_success "محدودیت‌های سیستم بهینه شدند"
}

# تنظیمات پیشرفته SSH
harden_ssh() {
    print_info "در حال امن‌سازی SSH..."
    
    # Backup کانفیگ اصلی
    cp /etc/ssh/sshd_config /etc/ssh/sshd_config.backup.$(date +%Y%m%d)
    
    cat > /etc/ssh/sshd_config.d/hardening.conf <<EOF
# SSH Hardening
Protocol 2
PermitRootLogin prohibit-password
PasswordAuthentication yes
PermitEmptyPasswords no
ChallengeResponseAuthentication no
UsePAM yes
X11Forwarding no
PrintMotd no
AcceptEnv LANG LC_*
Subsystem sftp /usr/lib/openssh/sftp-server

# Security
MaxAuthTries 3
MaxSessions 10
ClientAliveInterval 300
ClientAliveCountMax 2
LoginGraceTime 60

# Ciphers (Strong Only)
Ciphers chacha20-poly1305@openssh.com,aes256-gcm@openssh.com,aes128-gcm@openssh.com
MACs hmac-sha2-512-etm@openssh.com,hmac-sha2-256-etm@openssh.com
KexAlgorithms curve25519-sha256,curve25519-sha256@libssh.org
EOF
    
    # تست کانفیگ
    if sshd -t 2>/dev/null; then
        systemctl restart sshd
        print_success "SSH امن‌سازی شد"
    else
        print_error "خطا در کانفیگ SSH! بازگردانی تنظیمات قبلی..."
        rm /etc/ssh/sshd_config.d/hardening.conf
    fi
}

# نصب و پیکربندی پیشرفته Fail2ban
configure_fail2ban_advanced() {
    print_info "در حال پیکربندی پیشرفته Fail2ban..."
    
    # نصب در صورت نبودن
    if ! command -v fail2ban-client &> /dev/null; then
        apt-get install -y fail2ban
    fi
    
    cat > /etc/fail2ban/jail.local <<EOF
[DEFAULT]
bantime = 86400
findtime = 3600
maxretry = 3
destemail = root@localhost
sendername = Fail2Ban
action = %(action_mwl)s

[sshd]
enabled = true
port = ssh
logpath = /var/log/auth.log
maxretry = 3
bantime = 86400

[xray-reality]
enabled = true
port = 443,80,8080
logpath = /var/log/xray/access.log
maxretry = 50
findtime = 300
bantime = 3600
filter = xray-reality

[recidive]
enabled = true
logpath = /var/log/fail2ban.log
bantime = 604800
findtime = 86400
maxretry = 3
EOF
    
    # فیلتر سفارشی برای Xray
    cat > /etc/fail2ban/filter.d/xray-reality.conf <<EOF
[Definition]
failregex = ^.*rejected.*from <HOST>.*$
ignoreregex =
EOF
    
    systemctl restart fail2ban
    systemctl enable fail2ban
    
    print_success "Fail2ban پیکربندی شد"
}

# نصب و پیکربندی UFW پیشرفته
configure_ufw_advanced() {
    print_info "در حال پیکربندی فایروال پیشرفته..."
    
    # ریست فایروال
    ufw --force reset
    
    # تنظیمات پیش‌فرض
    ufw default deny incoming
    ufw default allow outgoing
    
    # SSH (اگر پورت سفارشی دارید، تغییر دهید)
    ufw allow 22/tcp comment 'SSH'
    
    # Xray (پورت پیش‌فرض - باید با کانفیگ شما تطابق داشته باشد)
    read -p "پورت Xray را وارد کنید (پیش‌فرض 443): " XRAY_PORT
    XRAY_PORT=${XRAY_PORT:-443}
    ufw allow ${XRAY_PORT}/tcp comment 'Xray Reality'
    
    # محدودیت Rate Limiting برای SSH
    ufw limit 22/tcp comment 'SSH Rate Limit'
    
    # لاگ کردن
    ufw logging medium
    
    # فعال‌سازی
    ufw --force enable
    
    print_success "فایروال پیکربندی شد"
}

# نصب ابزارهای مانیتورینگ
install_monitoring_tools() {
    print_info "در حال نصب ابزارهای مانیتورینگ..."
    
    apt-get install -y htop iotop iftop vnstat nethogs ncdu
    
    # فعال‌سازی vnstat
    systemctl enable vnstat
    systemctl start vnstat
    
    print_success "ابزارهای مانیتورینگ نصب شدند"
}

# پیکربندی Automatic Updates
configure_auto_updates() {
    print_info "در حال پیکربندی به‌روزرسانی خودکار..."
    
    apt-get install -y unattended-upgrades apt-listchanges
    
    cat > /etc/apt/apt.conf.d/50unattended-upgrades <<EOF
Unattended-Upgrade::Allowed-Origins {
    "\${distro_id}:\${distro_codename}-security";
    "\${distro_id}ESMApps:\${distro_codename}-apps-security";
};
Unattended-Upgrade::AutoFixInterruptedDpkg "true";
Unattended-Upgrade::MinimalSteps "true";
Unattended-Upgrade::Remove-Unused-Kernel-Packages "true";
Unattended-Upgrade::Remove-Unused-Dependencies "true";
Unattended-Upgrade::Automatic-Reboot "false";
EOF
    
    cat > /etc/apt/apt.conf.d/20auto-upgrades <<EOF
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Download-Upgradeable-Packages "1";
APT::Periodic::AutocleanInterval "7";
APT::Periodic::Unattended-Upgrade "1";
EOF
    
    systemctl enable unattended-upgrades
    systemctl start unattended-upgrades
    
    print_success "به‌روزرسانی خودکار فعال شد"
}

# ساخت اسکریپت مانیتورینگ
create_monitoring_script() {
    print_info "در حال ایجاد اسکریپت مانیتورینگ..."
    
    cat > /usr/local/bin/xray-monitor <<'EOF'
#!/bin/bash

echo "=========================================="
echo "  Xray Reality - System Monitor"
echo "=========================================="
echo

# System Info
echo "📊 System Resources:"
echo "----------------------------------------"
echo "CPU Usage: $(top -bn1 | grep "Cpu(s)" | awk '{print $2}' | cut -d'%' -f1)%"
echo "Memory: $(free -h | awk 'NR==2{printf "%s/%s (%.2f%%)", $3,$2,$3*100/$2}')"
echo "Disk: $(df -h / | awk 'NR==2{printf "%s/%s (%s)", $3,$2,$5}')"
echo

# Xray Status
echo "🚀 Xray Status:"
echo "----------------------------------------"
systemctl is-active --quiet xray && echo "Status: ✅ Running" || echo "Status: ❌ Stopped"
echo "Uptime: $(systemctl show xray -p ActiveEnterTimestamp | cut -d'=' -f2)"
echo

# Connections
echo "🔗 Active Connections:"
echo "----------------------------------------"
XRAY_PORT=$(jq -r '.inbounds[0].port' /usr/local/etc/xray/config.json 2>/dev/null || echo "443")
CONNECTIONS=$(ss -tn | grep ":${XRAY_PORT}" | wc -l)
echo "Active: $CONNECTIONS connections"
echo

# Traffic Stats (if vnstat is available)
if command -v vnstat &> /dev/null; then
    echo "📈 Traffic Statistics (Today):"
    echo "----------------------------------------"
    vnstat --oneline | awk -F';' '{print "Received: " $9 "\nSent: " $10 "\nTotal: " $11}'
    echo
fi

# Fail2ban Stats
if command -v fail2ban-client &> /dev/null; then
    echo "🛡️  Fail2ban Stats:"
    echo "----------------------------------------"
    fail2ban-client status sshd 2>/dev/null | grep "Currently banned" || echo "No banned IPs"
    echo
fi

# Recent Xray Logs
echo "📝 Recent Xray Logs (Last 10):"
echo "----------------------------------------"
journalctl -u xray -n 10 --no-pager --output short-iso
echo

echo "=========================================="
EOF
    
    chmod +x /usr/local/bin/xray-monitor
    
    print_success "اسکریپت مانیتورینگ ایجاد شد: xray-monitor"
}

# ساخت اسکریپت Backup خودکار
create_backup_script() {
    print_info "در حال ایجاد اسکریپت پشتیبان‌گیری..."
    
    cat > /usr/local/bin/xray-backup <<'EOF'
#!/bin/bash

BACKUP_DIR="/root/xray-backups"
DATE=$(date +%Y%m%d-%H%M%S)
BACKUP_FILE="${BACKUP_DIR}/xray-backup-${DATE}.tar.gz"

mkdir -p "$BACKUP_DIR"

# Backup Xray config and data
tar -czf "$BACKUP_FILE" \
    /usr/local/etc/xray/ \
    /root/xray-reality-info.txt \
    /etc/systemd/system/xray.service \
    2>/dev/null

if [ $? -eq 0 ]; then
    echo "✓ Backup created: $BACKUP_FILE"
    
    # Keep only last 7 backups
    cd "$BACKUP_DIR"
    ls -t | tail -n +8 | xargs -r rm --
    
    echo "✓ Old backups cleaned"
else
    echo "✗ Backup failed!"
    exit 1
fi
EOF
    
    chmod +x /usr/local/bin/xray-backup
    
    # اضافه کردن Cron Job برای backup روزانه
    (crontab -l 2>/dev/null; echo "0 3 * * * /usr/local/bin/xray-backup > /dev/null 2>&1") | crontab -
    
    print_success "اسکریپت پشتیبان‌گیری ایجاد شد: xray-backup"
    print_info "پشتیبان‌گیری خودکار هر روز ساعت 3 صبح انجام می‌شود"
}

# پاکسازی و بهینه‌سازی
cleanup_system() {
    print_info "در حال پاکسازی سیستم..."
    
    apt-get autoremove -y
    apt-get autoclean -y
    apt-get clean
    
    # پاکسازی لاگ‌های قدیمی
    journalctl --vacuum-time=7d
    
    # پاکسازی /tmp
    find /tmp -type f -atime +7 -delete 2>/dev/null || true
    
    print_success "سیستم پاکسازی شد"
}

# تنظیم Timezone
set_timezone() {
    print_info "تنظیم Timezone..."
    timedatectl set-timezone Asia/Tehran
    print_success "Timezone به Asia/Tehran تنظیم شد"
}

# نمایش خلاصه
show_summary() {
    clear
    echo "=========================================="
    print_success "بهینه‌سازی سرور با موفقیت انجام شد!"
    echo "=========================================="
    echo
    print_info "تغییرات اعمال شده:"
    echo "  ✓ بهینه‌سازی شبکه (TCP BBR)"
    echo "  ✓ افزایش محدودیت‌های سیستم"
    echo "  ✓ امن‌سازی SSH"
    echo "  ✓ پیکربندی Fail2ban"
    echo "  ✓ پیکربندی UFW Firewall"
    echo "  ✓ نصب ابزارهای مانیتورینگ"
    echo "  ✓ فعال‌سازی به‌روزرسانی خودکار"
    echo "  ✓ ایجاد اسکریپت‌های کمکی"
    echo
    print_info "دستورات مفید:"
    echo "  • xray-monitor         : نمایش وضعیت سیستم و Xray"
    echo "  • xray-backup          : پشتیبان‌گیری دستی"
    echo "  • htop                 : مانیتور منابع"
    echo "  • vnstat -l            : مانیتور ترافیک لحظه‌ای"
    echo "  • fail2ban-client status : وضعیت Fail2ban"
    echo
    print_warning "توصیه می‌شود سرور را ریبوت کنید: reboot"
    echo
}

# Main
main() {
    clear
    echo "=========================================="
    echo "  Server Optimization & Hardening"
    echo "=========================================="
    echo
    
    check_root
    
    optimize_network
    optimize_limits
    harden_ssh
    configure_fail2ban_advanced
    configure_ufw_advanced
    install_monitoring_tools
    configure_auto_updates
    create_monitoring_script
    create_backup_script
    set_timezone
    cleanup_system
    
    show_summary
}

main
