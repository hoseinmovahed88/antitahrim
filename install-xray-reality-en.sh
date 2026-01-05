#!/bin/bash

# Xray Reality Protocol - Auto Installation Script (English Version)
# For Ubuntu/Debian systems
# Version: 2.0

set -e

# Colors
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
        print_error "This script must be run as root."
        exit 1
    fi
}

check_os() {
    if [[ -f /etc/os-release ]]; then
        . /etc/os-release
        OS=$ID
        VER=$VERSION_ID
    else
        print_error "Operating system not detected!"
        exit 1
    fi

    if [[ "$OS" != "ubuntu" && "$OS" != "debian" ]]; then
        print_warning "This script is optimized for Ubuntu/Debian."
        read -p "Continue anyway? (y/n): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    fi
}

install_dependencies() {
    print_info "Installing dependencies..."
    
    apt-get update -qq
    apt-get install -y curl wget unzip jq qrencode ufw fail2ban -qq
    
    print_success "Dependencies installed"
}

install_xray() {
    print_info "Installing Xray-core..."
    
    systemctl stop xray 2>/dev/null || true
    rm -rf /usr/local/bin/xray /usr/local/etc/xray /var/log/xray
    
    XRAY_VERSION=$(curl -s https://api.github.com/repos/XTLS/Xray-core/releases/latest | jq -r .tag_name)
    DOWNLOAD_URL="https://github.com/XTLS/Xray-core/releases/download/${XRAY_VERSION}/Xray-linux-64.zip"
    
    wget -q --show-progress "$DOWNLOAD_URL" -O /tmp/xray.zip
    
    unzip -q /tmp/xray.zip -d /tmp/xray
    mv /tmp/xray/xray /usr/local/bin/
    chmod +x /usr/local/bin/xray
    
    mkdir -p /usr/local/etc/xray
    mkdir -p /var/log/xray
    
    rm -rf /tmp/xray /tmp/xray.zip
    
    print_success "Xray installed (${XRAY_VERSION})"
}

generate_reality_keys() {
    print_info "Generating Reality keys..."
    
    KEYS=$(/usr/local/bin/xray x25519)
    PRIVATE_KEY=$(echo "$KEYS" | grep "Private key:" | awk '{print $3}')
    PUBLIC_KEY=$(echo "$KEYS" | grep "Public key:" | awk '{print $3}')
    
    print_success "Keys generated"
}

generate_uuid() {
    cat /proc/sys/kernel/random/uuid
}

generate_short_id() {
    openssl rand -hex 8
}

get_user_input() {
    print_info "Please enter the following information:"
    echo
    
    read -p "Service port (default 443): " PORT
    PORT=${PORT:-443}
    
    print_info "Enter a valid domain for SNI (e.g., www.google.com or sni.bmi.ir)"
    read -p "SNI Domain: " SNI_DOMAIN
    SNI_DOMAIN=${SNI_DOMAIN:-www.google.com}
    
    USER_UUID=$(generate_uuid)
    print_info "User UUID: $USER_UUID"
    
    SHORT_ID=$(generate_short_id)
    
    echo
}

create_config() {
    print_info "Creating config file..."
    
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
    
    print_success "Config file created"
}

create_systemd_service() {
    print_info "Creating systemd service..."
    
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
    
    print_success "Systemd service created"
}

configure_firewall() {
    print_info "Configuring firewall..."
    
    ufw --force enable
    ufw allow ${PORT}/tcp
    ufw allow 22/tcp
    
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
    
    print_success "Firewall configured"
}

configure_fail2ban() {
    print_info "Configuring Fail2ban..."
    
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
    
    print_success "Fail2ban configured"
}

start_service() {
    print_info "Starting Xray service..."
    
    systemctl start xray
    sleep 2
    
    if systemctl is-active --quiet xray; then
        print_success "Xray service started successfully"
    else
        print_error "Error starting service. Check logs: journalctl -u xray -n 50"
        exit 1
    fi
}

show_connection_info() {
    SERVER_IP=$(curl -s4 ifconfig.me || curl -s4 icanhazip.com)
    
    VLESS_LINK="vless://${USER_UUID}@${SERVER_IP}:${PORT}?encryption=none&flow=xtls-rprx-vision&security=reality&sni=${SNI_DOMAIN}&fp=chrome&pbk=${PUBLIC_KEY}&sid=${SHORT_ID}&type=tcp&headerType=none#XrayReality"
    
    echo
    echo "=========================================="
    print_success "Installation completed successfully!"
    echo "=========================================="
    echo
    print_info "Connection information:"
    echo "----------------------------------------"
    echo "Server address: ${SERVER_IP}"
    echo "Port: ${PORT}"
    echo "UUID: ${USER_UUID}"
    echo "Public Key: ${PUBLIC_KEY}"
    echo "Short ID: ${SHORT_ID}"
    echo "SNI: ${SNI_DOMAIN}"
    echo "Flow: xtls-rprx-vision"
    echo "=========================================="
    echo
    print_info "Connection link (copy this):"
    echo "${VLESS_LINK}"
    echo
    
    cat > /root/xray-reality-info.txt <<EOF
Xray Reality - Connection Info
================================

Server address: ${SERVER_IP}
Port: ${PORT}
UUID: ${USER_UUID}
Public Key: ${PUBLIC_KEY}
Short ID: ${SHORT_ID}
SNI: ${SNI_DOMAIN}
Flow: xtls-rprx-vision

Connection link:
${VLESS_LINK}

Service management:
systemctl start/stop/restart xray
systemctl status xray
journalctl -u xray -f

User management:
./manage-users.sh
EOF
    
    print_success "Information saved to /root/xray-reality-info.txt"
    echo
    print_info "Show QR Code: qrencode -t ansiutf8 < /root/xray-reality-info.txt"
    echo
}

main() {
    clear
    echo "=========================================="
    echo "  Xray Reality - Auto Install"
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
    print_success "Installation complete! Enjoy your server 🚀"
    echo
}

main
