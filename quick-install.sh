#!/bin/bash

# Quick Install Script for Private Repository
# اسکریپت نصب سریع برای Repository خصوصی

set -e

echo "=========================================="
echo "  Xray Reality - Quick Install"
echo "=========================================="
echo

# درخواست توکن
read -sp "GitHub Personal Access Token خود را وارد کنید: " TOKEN
echo
echo

if [ -z "$TOKEN" ]; then
    echo "❌ توکن وارد نشد!"
    exit 1
fi

echo "📥 در حال دانلود فایل‌ها..."

# ساخت دایرکتوری موقت
TEMP_DIR="/tmp/xray-install-$$"
mkdir -p "$TEMP_DIR"
cd "$TEMP_DIR"

# دانلود اسکریپت‌ها
curl -H "Authorization: token $TOKEN" \
     -H "Accept: application/vnd.github.v3.raw" \
     -o install-xray-reality.sh \
     -L https://api.github.com/repos/hoseinmovahed88/antitahrim/contents/install-xray-reality.sh \
     2>/dev/null

if [ $? -ne 0 ]; then
    echo "❌ خطا در دانلود! توکن را بررسی کنید."
    exit 1
fi

curl -H "Authorization: token $TOKEN" \
     -H "Accept: application/vnd.github.v3.raw" \
     -o manage-users.sh \
     -L https://api.github.com/repos/hoseinmovahed88/antitahrim/contents/manage-users.sh \
     2>/dev/null

curl -H "Authorization: token $TOKEN" \
     -H "Accept: application/vnd.github.v3.raw" \
     -o optimize-server.sh \
     -L https://api.github.com/repos/hoseinmovahed88/antitahrim/contents/optimize-server.sh \
     2>/dev/null

chmod +x *.sh

echo "✓ فایل‌ها دانلود شدند"
echo

# کپی به دایرکتوری اصلی
cp *.sh /root/
cd /root
rm -rf "$TEMP_DIR"

echo "=========================================="
echo "✓ آماده نصب!"
echo "=========================================="
echo
echo "دستورات بعدی:"
echo "  1. نصب Xray:         ./install-xray-reality.sh"
echo "  2. بهینه‌سازی:       ./optimize-server.sh"
echo "  3. مدیریت کاربران:   ./manage-users.sh"
echo
