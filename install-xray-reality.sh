#!/bin/bash

# Xray Reality Protocol - Auto Installation Script
# برای سیستم‌های Ubuntu/Debian
# نسخه: 2.0

set -e

# رنگ‌ها برای نمایش بهتر
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# توابع کمکی
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

# بررسی دسترسی root
check_root() {
    if [[ $EUID -ne 0 ]]; then
        print_error "این اسکریپت باید با دسترسی root اجرا شود."
        exit 1
    fi
}

# بررسی سیستم عامل
check_os() {
    if [[ -f /etc/os-release ]]; then
        . /etc/os-release
        OS=$ID
        VER=$VERSION_ID
    else
        print_error "سیستم عامل شناسایی نشد!"
        exit 1
    fi

    if [[ "$OS" != "ubuntu" && "$OS" != "debian" ]]; then
        print_warning "این اسکریپت برای Ubuntu/Debian بهینه شده است."
        read -p "ادامه می‌دهید؟ (y/n): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    fi
}

# نصب وابستگی‌ها
install_dependencies() {
    print_info "در حال نصب وابستگی‌ها..."
    
    apt-get update -qq
    apt-get install -y curl wget unzip jq qrencode ufw fail2ban -qq
    
    print_success "وابستگی‌ها نصب شدند"
}

# نصب Xray
install_xray() {
    print_info "در حال نصب Xray-core..."
    
    # حذف نسخه قدیمی
    systemctl stop xray 2>/dev/null || true
    rm -rf /usr/local/bin/xray /usr/local/etc/xray /var/log/xray
    
    # دانلود آخرین نسخه
    XRAY_VERSION=$(curl -s https://api.github.com/repos/XTLS/Xray-core/releases/latest | jq -r .tag_name)
    DOWNLOAD_URL="https://github.com/XTLS/Xray-core/releases/download/${XRAY_VERSION}/Xray-linux-64.zip"
    
    wget -q --show-progress "$DOWNLOAD_URL" -O /tmp/xray.zip
    
    # استخراج فایل‌ها
    unzip -q /tmp/xray.zip -d /tmp/xray
    mv /tmp/xray/xray /usr/local/bin/
    chmod +x /usr/local/bin/xray
    
    # ایجاد دایرکتوری‌های مورد نیاز
    mkdir -p /usr/local/etc/xray
    mkdir -p /var/log/xray
    
    # پاکسازی
    rm -rf /tmp/xray /tmp/xray.zip
    
    print_success "Xray نصب شد (${XRAY_VERSION})"
}

# تولید کلیدهای Reality
generate_reality_keys() {
    print_info "در حال تولید کلیدهای Reality..."
    
    KEYS=$(/usr/local/bin/xray x25519)
    PRIVATE_KEY=$(echo "$KEYS" | grep "Private key:" | awk '{print $3}')
    PUBLIC_KEY=$(echo "$KEYS" | grep "Public key:" | awk '{print $3}')
    
    print_success "کلیدها تولید شدند"
}

# تولید UUID برای کاربر
generate_uuid() {
    cat /proc/sys/kernel/random/uuid
}

# تولید shortIds
generate_short_id() {
    openssl rand -hex 8
}

# دریافت اطلاعات از کاربر
get_user_input() {
    print_info "لطفاً اطلاعات زیر را وارد کنید:"
    echo
    
    # پورت
    read -p "پورت سرویس (پیش‌فرض 443): " PORT
    PORT=${PORT:-443}
    
    # دامنه هدف برای SNI (یک سایت معتبر)
    print_info "یک دامنه معتبر برای SNI وارد کنید (مثل: www.google.com)"
    read -p "دامنه SNI: " SNI_DOMAIN
    SNI_DOMAIN=${SNI_DOMAIN:-www.google.com}
    
    # UUID کاربر
    USER_UUID=$(generate_uuid)
    print_info "UUID کاربر: $USER_UUID"
    
    # Short IDs
    SHORT_ID=$(generate_short_id)
    
    echo
}

# ایجاد فایل کانفیگ
create_config() {
    print_info "در حال ایجاد فایل کانفیگ..."
    
    cat > /usr/local/etc/xray/config.json <<EOF
{
  "log": {
    "loglevel": "warning",
    "access": "/var/log/xray/access.log",
    "error": "/var/log/xray/error.log"
  },
  "inbounds": [
    {
      "port": ${PORT},
      "protocol": "vless",
      "settings": {
        "clients": [
          {
            "id": "${USER_UUID}",
            "flow": "xtls-rprx-vision"
          }
        ],
        "decryption": "none"
      },
      "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
          "show": false,
          "dest": "${SNI_DOMAIN}:443",
          "xver": 0,
          "serverNames": [
            "${SNI_DOMAIN}"
          ],
          "privateKey": "${PRIVATE_KEY}",
          "shortIds": [
            "${SHORT_ID}",
            ""
          ]
        }
      },
      "sniffing": {
        "enabled": true,
        "destOverride": [
          "http",
          "tls",
          "quic"
        ]
      }
    }
  ],
  "outbounds": [
    {
      "protocol": "freedom",
      "tag": "direct"
    },
    {
      "protocol": "blackhole",
      "tag": "block"
    }
  ],
  "routing": {
    "domainStrategy": "IPIfNonMatch",
    "rules": [
      {
        "type": "field",
        "ip": [
          "geoip:private"
        ],
        "outboundTag": "block"
      },
      {
        "type": "field",
        "protocol": [
          "bittorrent"
        ],
        "outboundTag": "block"
      }
    ]
  }
}
EOF
    
    print_success "فایل کانفیگ ایجاد شد"
}

# ایجاد سرویس systemd
create_systemd_service() {
    print_info "در حال ایجاد سرویس systemd..."
    
    cat > /etc/systemd/system/xray.service <<EOF
[Unit]
Description=Xray Service
Documentation=https://github.com/xtls
After=network.target nss-lookup.target

[Service]
Type=simple
User=root
CapabilityBoundingSet=CAP_NET_ADMIN CAP_NET_BIND_SERVICE
AmbientCapabilities=CAP_NET_ADMIN CAP_NET_BIND_SERVICE
NoNewPrivileges=true
ExecStart=/usr/local/bin/xray run -config /usr/local/etc/xray/config.json
Restart=on-failure
RestartPreventExitStatus=23
LimitNOFILE=1000000

[Install]
WantedBy=multi-user.target
EOF
    
    systemctl daemon-reload
    systemctl enable xray
    
    print_success "سرویس systemd ایجاد شد"
}

# تنظیم فایروال
configure_firewall() {
    print_info "در حال تنظیم فایروال..."
    
    # فعال‌سازی UFW
    ufw --force enable
    
    # باز کردن پورت‌های مورد نیاز
    ufw allow ${PORT}/tcp
    ufw allow 22/tcp
    
    # بهینه‌سازی‌های شبکه
    cat >> /etc/sysctl.conf <<EOF

# Xray Optimization
net.ipv4.tcp_fastopen = 3
net.ipv4.tcp_congestion_control = bbr
net.core.default_qdisc = fq
net.ipv4.tcp_keepalive_time = 600
net.ipv4.tcp_keepalive_intvl = 60
net.ipv4.tcp_keepalive_probes = 10
EOF
    
    sysctl -p > /dev/null 2>&1
    
    print_success "فایروال تنظیم شد"
}

# تنظیم Fail2ban
configure_fail2ban() {
    print_info "در حال تنظیم Fail2ban..."
    
    cat > /etc/fail2ban/jail.local <<EOF
[DEFAULT]
bantime = 3600
findtime = 600
maxretry = 5

[sshd]
enabled = true
EOF
    
    systemctl restart fail2ban
    systemctl enable fail2ban
    
    print_success "Fail2ban تنظیم شد"
}

# شروع سرویس
start_service() {
    print_info "در حال شروع سرویس Xray..."
    
    systemctl start xray
    sleep 2
    
    if systemctl is-active --quiet xray; then
        print_success "سرویس Xray با موفقیت شروع شد"
    else
        print_error "خطا در شروع سرویس. لاگ‌ها را بررسی کنید: journalctl -u xray -n 50"
        exit 1
    fi
}

# نمایش اطلاعات اتصال
show_connection_info() {
    SERVER_IP=$(curl -s4 ifconfig.me || curl -s4 icanhazip.com)
    
    # ساخت لینک VLESS
    VLESS_LINK="vless://${USER_UUID}@${SERVER_IP}:${PORT}?encryption=none&flow=xtls-rprx-vision&security=reality&sni=${SNI_DOMAIN}&fp=chrome&pbk=${PUBLIC_KEY}&sid=${SHORT_ID}&type=tcp&headerType=none#XrayReality"
    
    echo
    echo "=========================================="
    print_success "نصب با موفقیت انجام شد!"
    echo "=========================================="
    echo
    print_info "اطلاعات اتصال:"
    echo "----------------------------------------"
    echo "آدرس سرور: ${SERVER_IP}"
    echo "پورت: ${PORT}"
    echo "UUID: ${USER_UUID}"
    echo "Public Key: ${PUBLIC_KEY}"
    echo "Short ID: ${SHORT_ID}"
    echo "SNI: ${SNI_DOMAIN}"
    echo "Flow: xtls-rprx-vision"
    echo "=========================================="
    echo
    print_info "لینک اتصال (کپی کنید):"
    echo "${VLESS_LINK}"
    echo
    
    # ذخیره در فایل
    cat > /root/xray-reality-info.txt <<EOF
Xray Reality - اطلاعات اتصال
================================

آدرس سرور: ${SERVER_IP}
پورت: ${PORT}
UUID: ${USER_UUID}
Public Key: ${PUBLIC_KEY}
Short ID: ${SHORT_ID}
SNI: ${SNI_DOMAIN}
Flow: xtls-rprx-vision

لینک اتصال:
${VLESS_LINK}

برای مدیریت سرویس:
systemctl start/stop/restart xray
systemctl status xray
journalctl -u xray -f

برای مدیریت کاربران:
./manage-users.sh
EOF
    
    print_success "اطلاعات در فایل /root/xray-reality-info.txt ذخیره شد"
    echo
    print_info "برای نمایش QR Code: qrencode -t ansiutf8 < /root/xray-reality-info.txt"
    echo
}

# تابع اصلی
main() {
    clear
    echo "=========================================="
    echo "  Xray Reality - نصب خودکار"
    echo "=========================================="
    echo
    
    check_root
    check_os
    install_dependencies
    install_xray
    generate_reality_keys
    get_user_input
    create_config
    create_systemd_service
    configure_firewall
    configure_fail2ban
    start_service
    show_connection_info
    
    echo
    print_success "نصب کامل شد! از سرور خود لذت ببرید 🚀"
    echo
}

# اجرای برنامه
main
