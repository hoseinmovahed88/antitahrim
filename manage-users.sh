#!/bin/bash

# Xray Reality - User Management Script
# اسکریپت مدیریت کاربران

set -e

CONFIG_FILE="/usr/local/etc/xray/config.json"
INFO_FILE="/root/xray-reality-info.txt"

# رنگ‌ها
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

# بررسی دسترسی root
if [[ $EUID -ne 0 ]]; then
    print_error "این اسکریپت باید با دسترسی root اجرا شود."
    exit 1
fi

# بررسی وجود Xray
if [ ! -f "$CONFIG_FILE" ]; then
    print_error "فایل کانفیگ Xray پیدا نشد. ابتدا اسکریپت نصب را اجرا کنید."
    exit 1
fi

# تولید UUID جدید
generate_uuid() {
    cat /proc/sys/kernel/random/uuid
}

# دریافت اطلاعات سرور
get_server_info() {
    SERVER_IP=$(curl -s4 ifconfig.me 2>/dev/null || curl -s4 icanhazip.com 2>/dev/null || echo "SERVER_IP")
    PORT=$(jq -r '.inbounds[0].port' "$CONFIG_FILE")
    PUBLIC_KEY=$(jq -r '.inbounds[0].streamSettings.realitySettings.privateKey' "$CONFIG_FILE" | xargs /usr/local/bin/xray x25519 -i | grep "Public key:" | awk '{print $3}')
    SNI=$(jq -r '.inbounds[0].streamSettings.realitySettings.serverNames[0]' "$CONFIG_FILE")
    SHORT_ID=$(jq -r '.inbounds[0].streamSettings.realitySettings.shortIds[0]' "$CONFIG_FILE")
}

# لیست کاربران
list_users() {
    clear
    echo "=========================================="
    echo "  لیست کاربران فعال"
    echo "=========================================="
    echo
    
    USERS=$(jq -r '.inbounds[0].settings.clients[] | "\(.id)"' "$CONFIG_FILE" 2>/dev/null)
    
    if [ -z "$USERS" ]; then
        print_warning "هیچ کاربری یافت نشد."
        return
    fi
    
    i=1
    while IFS= read -r uuid; do
        echo "$i. UUID: $uuid"
        ((i++))
    done <<< "$USERS"
    
    echo
}

# اضافه کردن کاربر جدید
add_user() {
    clear
    echo "=========================================="
    echo "  اضافه کردن کاربر جدید"
    echo "=========================================="
    echo
    
    NEW_UUID=$(generate_uuid)
    print_info "UUID جدید: $NEW_UUID"
    
    # اضافه کردن به کانفیگ
    jq --arg uuid "$NEW_UUID" \
       '.inbounds[0].settings.clients += [{"id": $uuid, "flow": "xtls-rprx-vision"}]' \
       "$CONFIG_FILE" > "$CONFIG_FILE.tmp" && mv "$CONFIG_FILE.tmp" "$CONFIG_FILE"
    
    # ریستارت سرویس
    systemctl restart xray
    sleep 2
    
    if systemctl is-active --quiet xray; then
        print_success "کاربر جدید با موفقیت اضافه شد!"
        
        # دریافت اطلاعات و ساخت لینک
        get_server_info
        VLESS_LINK="vless://${NEW_UUID}@${SERVER_IP}:${PORT}?encryption=none&flow=xtls-rprx-vision&security=reality&sni=${SNI}&fp=chrome&pbk=${PUBLIC_KEY}&sid=${SHORT_ID}&type=tcp&headerType=none#XrayReality-User"
        
        echo
        echo "=========================================="
        print_info "لینک اتصال:"
        echo "${VLESS_LINK}"
        echo "=========================================="
        echo
        
        # نمایش QR Code
        echo "$VLESS_LINK" | qrencode -t ansiutf8
        
        # ذخیره در فایل
        echo "" >> "$INFO_FILE"
        echo "کاربر جدید - $(date)" >> "$INFO_FILE"
        echo "UUID: $NEW_UUID" >> "$INFO_FILE"
        echo "Link: $VLESS_LINK" >> "$INFO_FILE"
        
    else
        print_error "خطا در ریستارت سرویس!"
        # بازگردانی تغییرات
        jq --arg uuid "$NEW_UUID" \
           '.inbounds[0].settings.clients -= [{"id": $uuid, "flow": "xtls-rprx-vision"}]' \
           "$CONFIG_FILE" > "$CONFIG_FILE.tmp" && mv "$CONFIG_FILE.tmp" "$CONFIG_FILE"
    fi
}

# حذف کاربر
remove_user() {
    clear
    list_users
    
    if [ $(jq '.inbounds[0].settings.clients | length' "$CONFIG_FILE") -eq 0 ]; then
        return
    fi
    
    echo
    read -p "شماره کاربر برای حذف (یا 0 برای بازگشت): " USER_NUM
    
    if [ "$USER_NUM" = "0" ]; then
        return
    fi
    
    UUID_TO_REMOVE=$(jq -r ".inbounds[0].settings.clients[$((USER_NUM-1))].id" "$CONFIG_FILE")
    
    if [ "$UUID_TO_REMOVE" = "null" ] || [ -z "$UUID_TO_REMOVE" ]; then
        print_error "شماره نامعتبر!"
        sleep 2
        return
    fi
    
    read -p "آیا مطمئن هستید که می‌خواهید این کاربر را حذف کنید؟ (y/n): " -n 1 -r
    echo
    
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        jq --arg uuid "$UUID_TO_REMOVE" \
           '.inbounds[0].settings.clients = [.inbounds[0].settings.clients[] | select(.id != $uuid)]' \
           "$CONFIG_FILE" > "$CONFIG_FILE.tmp" && mv "$CONFIG_FILE.tmp" "$CONFIG_FILE"
        
        systemctl restart xray
        sleep 2
        
        if systemctl is-active --quiet xray; then
            print_success "کاربر حذف شد!"
        else
            print_error "خطا در ریستارت سرویس!"
        fi
    fi
    
    sleep 2
}

# نمایش اطلاعات کاربر
show_user_info() {
    clear
    list_users
    
    if [ $(jq '.inbounds[0].settings.clients | length' "$CONFIG_FILE") -eq 0 ]; then
        return
    fi
    
    echo
    read -p "شماره کاربر برای نمایش اطلاعات (یا 0 برای بازگشت): " USER_NUM
    
    if [ "$USER_NUM" = "0" ]; then
        return
    fi
    
    USER_UUID=$(jq -r ".inbounds[0].settings.clients[$((USER_NUM-1))].id" "$CONFIG_FILE")
    
    if [ "$USER_UUID" = "null" ] || [ -z "$USER_UUID" ]; then
        print_error "شماره نامعتبر!"
        sleep 2
        return
    fi
    
    get_server_info
    VLESS_LINK="vless://${USER_UUID}@${SERVER_IP}:${PORT}?encryption=none&flow=xtls-rprx-vision&security=reality&sni=${SNI}&fp=chrome&pbk=${PUBLIC_KEY}&sid=${SHORT_ID}&type=tcp&headerType=none#XrayReality"
    
    clear
    echo "=========================================="
    echo "  اطلاعات کاربر"
    echo "=========================================="
    echo
    echo "آدرس سرور: ${SERVER_IP}"
    echo "پورت: ${PORT}"
    echo "UUID: ${USER_UUID}"
    echo "Public Key: ${PUBLIC_KEY}"
    echo "Short ID: ${SHORT_ID}"
    echo "SNI: ${SNI}"
    echo "=========================================="
    echo
    print_info "لینک اتصال:"
    echo "${VLESS_LINK}"
    echo
    echo "=========================================="
    echo
    print_info "QR Code:"
    echo "$VLESS_LINK" | qrencode -t ansiutf8
    echo
    
    read -p "برای بازگشت Enter بزنید..."
}

# نمایش وضعیت سرویس
show_status() {
    clear
    echo "=========================================="
    echo "  وضعیت سرویس Xray"
    echo "=========================================="
    echo
    
    systemctl status xray --no-pager
    
    echo
    echo "=========================================="
    echo "  آخرین لاگ‌ها"
    echo "=========================================="
    echo
    
    journalctl -u xray -n 20 --no-pager
    
    echo
    read -p "برای بازگشت Enter بزنید..."
}

# تنظیمات پیشرفته
advanced_settings() {
    while true; do
        clear
        echo "=========================================="
        echo "  تنظیمات پیشرفته"
        echo "=========================================="
        echo
        echo "1. تغییر پورت"
        echo "2. تغییر SNI Domain"
        echo "3. تولید کلیدهای جدید"
        echo "4. نمایش کانفیگ کامل"
        echo "5. پشتیبان‌گیری از کانفیگ"
        echo "0. بازگشت"
        echo
        read -p "انتخاب کنید: " choice
        
        case $choice in
            1) change_port ;;
            2) change_sni ;;
            3) regenerate_keys ;;
            4) show_config ;;
            5) backup_config ;;
            0) break ;;
            *) print_error "گزینه نامعتبر!" && sleep 1 ;;
        esac
    done
}

change_port() {
    echo
    read -p "پورت جدید را وارد کنید: " NEW_PORT
    
    if ! [[ "$NEW_PORT" =~ ^[0-9]+$ ]] || [ "$NEW_PORT" -lt 1 ] || [ "$NEW_PORT" -gt 65535 ]; then
        print_error "پورت نامعتبر!"
        sleep 2
        return
    fi
    
    OLD_PORT=$(jq -r '.inbounds[0].port' "$CONFIG_FILE")
    
    jq --arg port "$NEW_PORT" '.inbounds[0].port = ($port | tonumber)' \
       "$CONFIG_FILE" > "$CONFIG_FILE.tmp" && mv "$CONFIG_FILE.tmp" "$CONFIG_FILE"
    
    # به‌روزرسانی فایروال
    ufw delete allow ${OLD_PORT}/tcp 2>/dev/null || true
    ufw allow ${NEW_PORT}/tcp
    
    systemctl restart xray
    sleep 2
    
    if systemctl is-active --quiet xray; then
        print_success "پورت با موفقیت تغییر کرد!"
    else
        print_error "خطا در تغییر پورت!"
        jq --arg port "$OLD_PORT" '.inbounds[0].port = ($port | tonumber)' \
           "$CONFIG_FILE" > "$CONFIG_FILE.tmp" && mv "$CONFIG_FILE.tmp" "$CONFIG_FILE"
        ufw allow ${OLD_PORT}/tcp
        systemctl restart xray
    fi
    
    sleep 2
}

change_sni() {
    echo
    print_info "دامنه SNI جدید را وارد کنید (مثل: www.cloudflare.com):"
    read -p "SNI: " NEW_SNI
    
    jq --arg sni "$NEW_SNI" \
       '.inbounds[0].streamSettings.realitySettings.serverNames = [$sni] | 
        .inbounds[0].streamSettings.realitySettings.dest = "\($sni):443"' \
       "$CONFIG_FILE" > "$CONFIG_FILE.tmp" && mv "$CONFIG_FILE.tmp" "$CONFIG_FILE"
    
    systemctl restart xray
    sleep 2
    
    if systemctl is-active --quiet xray; then
        print_success "SNI با موفقیت تغییر کرد!"
    else
        print_error "خطا! لاگ‌ها را بررسی کنید."
    fi
    
    sleep 2
}

regenerate_keys() {
    echo
    print_warning "با تولید کلیدهای جدید، تمام کاربران باید کانفیگ جدید دریافت کنند!"
    read -p "ادامه می‌دهید؟ (y/n): " -n 1 -r
    echo
    
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        return
    fi
    
    KEYS=$(/usr/local/bin/xray x25519)
    NEW_PRIVATE_KEY=$(echo "$KEYS" | grep "Private key:" | awk '{print $3}')
    NEW_PUBLIC_KEY=$(echo "$KEYS" | grep "Public key:" | awk '{print $3}')
    
    jq --arg key "$NEW_PRIVATE_KEY" \
       '.inbounds[0].streamSettings.realitySettings.privateKey = $key' \
       "$CONFIG_FILE" > "$CONFIG_FILE.tmp" && mv "$CONFIG_FILE.tmp" "$CONFIG_FILE"
    
    systemctl restart xray
    sleep 2
    
    if systemctl is-active --quiet xray; then
        print_success "کلیدها تولید شدند!"
        echo
        print_info "Public Key جدید: $NEW_PUBLIC_KEY"
    else
        print_error "خطا در تولید کلیدها!"
    fi
    
    echo
    read -p "برای بازگشت Enter بزنید..."
}

show_config() {
    clear
    echo "=========================================="
    echo "  کانفیگ کامل Xray"
    echo "=========================================="
    echo
    
    jq '.' "$CONFIG_FILE"
    
    echo
    read -p "برای بازگشت Enter بزنید..."
}

backup_config() {
    BACKUP_FILE="/root/xray-config-backup-$(date +%Y%m%d-%H%M%S).json"
    cp "$CONFIG_FILE" "$BACKUP_FILE"
    
    print_success "پشتیبان در $BACKUP_FILE ذخیره شد"
    sleep 2
}

# منوی اصلی
main_menu() {
    while true; do
        clear
        echo "=========================================="
        echo "  Xray Reality - مدیریت کاربران"
        echo "=========================================="
        echo
        echo "1. لیست کاربران"
        echo "2. اضافه کردن کاربر جدید"
        echo "3. حذف کاربر"
        echo "4. نمایش اطلاعات و لینک کاربر"
        echo "5. وضعیت سرویس"
        echo "6. تنظیمات پیشرفته"
        echo "0. خروج"
        echo
        read -p "انتخاب کنید: " choice
        
        case $choice in
            1) list_users && read -p "برای بازگشت Enter بزنید..." ;;
            2) add_user ;;
            3) remove_user ;;
            4) show_user_info ;;
            5) show_status ;;
            6) advanced_settings ;;
            0) exit 0 ;;
            *) print_error "گزینه نامعتبر!" && sleep 1 ;;
        esac
    done
}

# اجرا
main_menu
