# 🚀 راهنمای کامل نصب روی سرور

## گام 1️⃣: دریافت Personal Access Token

1. به GitHub بروید: https://github.com/settings/tokens
2. روی **"Generate new token"** کلیک کنید
3. روی **"Generate new token (classic)"** کلیک کنید
4. نام بدهید: مثلاً `VPS Server Access`
5. Expiration را `No expiration` یا مدت دلخواه انتخاب کنید
6. در قسمت **Select scopes**، فقط `repo` را تیک بزنید (برای دسترسی به repository خصوصی)
7. روی **"Generate token"** کلیک کنید
8. ⚠️ توکن را کپی کنید (فقط یکبار نشان داده می‌شود!)

توکن شما شبیه این است: `ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`

---

## گام 2️⃣: اتصال به سرور

از ترمینال یا PuTTY به سرور متصل شوید:

```bash
ssh root@YOUR_SERVER_IP
```

یا اگر پورت سفارشی دارید:

```bash
ssh -p PORT root@YOUR_SERVER_IP
```

---

## گام 3️⃣: نصب (3 روش)

### 🥇 روش اول: نصب سریع (راحت‌ترین - توصیه می‌شود)

```bash
# دانلود و اجرای اسکریپت نصب سریع
bash <(curl -s https://raw.githubusercontent.com/hoseinmovahed88/antitahrim/main/quick-install.sh)

# توکن خود را وارد کنید وقتی از شما خواسته شد
```

سپس:

```bash
# نصب Xray
./install-xray-reality.sh

# (اختیاری) بهینه‌سازی سرور
./optimize-server.sh

# مدیریت کاربران
./manage-users.sh
```

---

### 🥈 روش دوم: Clone کامل پروژه

```bash
# نصب git
apt update && apt install -y git

# Clone پروژه
git clone https://github.com/hoseinmovahed88/antitahrim.git

# Git از شما می‌پرسد:
# Username for 'https://github.com': hoseinmovahed88
# Password for 'https://hoseinmovahed88@github.com': [توکن خود را پیست کنید]

# ورود به پوشه
cd antitahrim

# اجازه اجرا
chmod +x *.sh

# نصب
./install-xray-reality.sh
```

---

### 🥉 روش سوم: دانلود دستی با curl

```bash
# تنظیم متغیر توکن (یکبار)
export TOKEN="YOUR_GITHUB_TOKEN_HERE"

# دانلود اسکریپت نصب
curl -H "Authorization: token $TOKEN" \
     -H "Accept: application/vnd.github.v3.raw" \
     -o install-xray-reality.sh \
     -L https://api.github.com/repos/hoseinmovahed88/antitahrim/contents/install-xray-reality.sh

# دانلود اسکریپت مدیریت
curl -H "Authorization: token $TOKEN" \
     -H "Accept: application/vnd.github.v3.raw" \
     -o manage-users.sh \
     -L https://api.github.com/repos/hoseinmovahed88/antitahrim/contents/manage-users.sh

# دانلود اسکریپت بهینه‌سازی
curl -H "Authorization: token $TOKEN" \
     -H "Accept: application/vnd.github.v3.raw" \
     -o optimize-server.sh \
     -L https://api.github.com/repos/hoseinmovahed88/antitahrim/contents/optimize-server.sh

# اجازه اجرا
chmod +x *.sh

# نصب
./install-xray-reality.sh
```

---

## گام 4️⃣: اجرای نصب

پس از دانلود فایل‌ها:

```bash
# 1. نصب Xray Reality
./install-xray-reality.sh
```

اسکریپت از شما می‌پرسد:
- **پورت**: پیشنهاد `443` یا `8443` 
- **SNI Domain**: مثل `www.google.com` یا `www.cloudflare.com`

پس از نصب، اطلاعات اتصال و لینک VLESS را دریافت می‌کنید.

```bash
# 2. بهینه‌سازی سرور (اختیاری اما توصیه می‌شود)
./optimize-server.sh
```

این کار:
- TCP BBR را فعال می‌کند
- فایروال را تنظیم می‌کند
- Fail2ban را نصب می‌کند
- SSH را امن می‌کند
- به‌روزرسانی خودکار را فعال می‌کند

```bash
# 3. مدیریت کاربران
./manage-users.sh
```

از این منو می‌توانید:
- کاربر جدید اضافه کنید
- لینک و QR Code دریافت کنید
- کاربر حذف کنید
- تنظیمات را تغییر دهید

---

## گام 5️⃣: دریافت لینک اتصال

پس از نصب، اطلاعات اتصال در فایل زیر ذخیره شده:

```bash
cat /root/xray-reality-info.txt
```

همچنین می‌توانید با اسکریپت مدیریت، لینک و QR Code دریافت کنید:

```bash
./manage-users.sh
# انتخاب گزینه 4: نمایش اطلاعات و لینک کاربر
```

---

## گام 6️⃣: نصب کلاینت

### 📱 اندروید
1. دانلود **v2rayNG**: https://github.com/2dust/v2rayNG/releases
2. نصب برنامه
3. روی `+` کلیک → **Import config from Clipboard**
4. لینک VLESS را paste کنید
5. روی کانفیگ کلیک کنید و **اتصال** بزنید

### 🍎 iOS
1. دانلود **Streisand** یا **FoXray** از App Store
2. اسکن QR Code یا پیست لینک

### 💻 ویندوز
1. دانلود **v2rayN**: https://github.com/2dust/v2rayN/releases
2. نصب .NET 6.0 Runtime (اگر لازم است)
3. اجرای برنامه
4. Servers → **Add server via clipboard**
5. لینک را paste کنید

### 🍏 macOS
1. دانلود **V2Box** یا **FoXray**
2. پیست لینک یا اسکن QR Code

### 🐧 Linux
1. نصب **v2rayA**:
```bash
wget -qO - https://apt.v2raya.org/key/public-key.asc | sudo apt-key add -
sudo add-apt-repository 'deb https://apt.v2raya.org/ v2raya main'
sudo apt update
sudo apt install v2raya
```
2. باز کردن: http://localhost:2017
3. اضافه کردن سرور با لینک VLESS

---

## 🔧 دستورات مفید

### مدیریت سرویس
```bash
systemctl status xray       # بررسی وضعیت
systemctl restart xray      # ریستارت
journalctl -u xray -f       # مشاهده لاگ‌های لحظه‌ای
```

### مانیتورینگ
```bash
xray-monitor               # نمایش وضعیت کامل (بعد از optimize)
htop                       # مصرف CPU و RAM
vnstat -l                  # ترافیک لحظه‌ای
```

### پشتیبان‌گیری
```bash
xray-backup               # پشتیبان‌گیری دستی
cat /root/xray-reality-info.txt  # اطلاعات اتصال
```

---

## ⚠️ نکات امنیتی

1. ✅ هرگز توکن GitHub خود را به اشتراک نگذارید
2. ✅ پس از نصب، می‌توانید توکن را revoke کنید
3. ✅ از پورت‌های استاندارد استفاده کنید (443، 8443)
4. ✅ پسورد root را قوی انتخاب کنید
5. ✅ به‌طور منظم سرور را به‌روز کنید: `apt update && apt upgrade`
6. ✅ فقط به افراد مورد اعتماد دسترسی بدهید

---

## 🆘 عیب‌یابی

### سرویس شروع نمی‌شود
```bash
systemctl status xray
journalctl -u xray -n 50
```

### کلاینت متصل نمی‌شود
```bash
# بررسی پورت
ss -tulpn | grep xray

# بررسی فایروال
ufw status

# تست اتصال
curl -I https://YOUR_SERVER_IP:YOUR_PORT
```

### فراموش کردن اطلاعات
```bash
cat /root/xray-reality-info.txt
./manage-users.sh  # گزینه 4
```

---

## 📞 پشتیبانی

- 📖 راهنمای کامل: [README-FA.md](README-FA.md)
- 🐛 گزارش مشکل: [GitHub Issues](https://github.com/hoseinmovahed88/antitahrim/issues)

---

**موفق باشید! 🚀**
