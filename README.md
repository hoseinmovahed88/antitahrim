# 🚀 Xray Reality - Anti-Censorship Server

<div dir="rtl">

## یک راه‌حل کامل و غیرقابل شناسایی برای دسترسی آزاد به اینترنت

</div>

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/Platform-Linux-blue.svg)](https://www.linux.org/)
[![Protocol](https://img.shields.io/badge/Protocol-Xray%20Reality-green.svg)](https://github.com/XTLS/Xray-core)

<div dir="rtl">

## 📚 مستندات

- [راهنمای فارسی (کامل)](README-FA.md)
- [English Documentation](#english-documentation)

## ✨ ویژگی‌ها

- 🔒 **امنیت بالا**: پروتکل Reality با رمزنگاری پیشرفته
- 🌍 **غیرقابل تشخیص**: ترافیک 100% شبیه HTTPS واقعی
- ⚡ **سرعت بالا**: بدون overhead، سریع‌تر از سایر پروتکل‌ها
- 🛡️ **ضد فیلترینگ**: مقاوم در برابر DPI و فیلترینگ عمیق
- 🎯 **نصب آسان**: نصب خودکار با یک دستور
- 🔧 **مدیریت ساده**: پنل مدیریت کاربران با رابط متنی
- 📱 **پشتیبانی همه‌جانبه**: کلاینت‌های موبایل و دسکتاپ
- 🎮 **پلی‌استیشن**: دروازه اختصاصی برای PS4/PS5 بدون خراب کردن NAT Type
- 📱 **اندروید بدون سرور**: اپلیکیشنی که بدون VPS و بدون کانفیگ کار می‌کند

## 📜 اسکریپت‌های موجود

**روی سرور خارج:**
- **install-xray-reality-en.sh** - نصب خودکار Xray Reality (انگلیسی)
- **install-panel.sh** - نصب پنل وب 3X-UI
- **optimize-speed.sh** - بهینه‌سازی سرعت و شبکه
- **manage-users.sh** - مدیریت کاربران
- **fix-ssh.sh** - رفع مشکلات SSH

**اپلیکیشن اندروید (بدون سرور):**
- **android/** - فیلترشکن اندروید که سرور و کانفیگ نمی‌خواهد

**روی دستگاه خانگی (برای کنسول):**
- **playstation/install-ps-gateway.sh** - نصب دروازه پلی‌استیشن
- **playstation/manage-ps.sh** - منوی مدیریت دروازه
- **playstation/check-psn.sh** - تست و عیب‌یابی دسترسی به PSN

📖 **راهنماها:**
- [README-FA.md](README-FA.md) - راهنمای کامل فارسی
- [INSTALL-GUIDE.md](INSTALL-GUIDE.md) - راهنمای نصب گام‌به‌گام
- [PANEL-GUIDE.md](PANEL-GUIDE.md) - راهنمای پنل 3X-UI
- [PLAYSTATION-GUIDE.md](PLAYSTATION-GUIDE.md) - راهنمای باز کردن پلی‌استیشن 🎮
- [ANDROID-GUIDE.md](ANDROID-GUIDE.md) - اپلیکیشن اندروید بدون سرور 📱

---

## 🚀 نصب سریع

### مرحله 1: اتصال به سرور

```bash
ssh root@YOUR_SERVER_IP
```

### مرحله 2: دانلود پروژه

چون این repository خصوصی است، نیاز به احراز هویت دارید:

```bash
# نصب git
apt update && apt install -y git

# Clone پروژه (نیاز به Personal Access Token)
git clone https://github.com/hoseinmovahed88/antitahrim.git
cd antitahrim
chmod +x *.sh

# Git از شما username و token می‌خواهد
# Username: hoseinmovahed88
# Password: [Your Personal Access Token]
```

**نحوه ساخت Personal Access Token:**
1. https://github.com/settings/tokens
2. Generate new token → Classic
3. انتخاب scope: `repo`
4. Generate token و کپی کردن

### مرحله 3: نصب Xray Reality

```bash
./install-xray-reality.sh
```

### مرحله 4: بهینه‌سازی سرور (اختیاری اما توصیه می‌شود)

```bash
./optimize-server.sh
```

### مرحله 5: مدیریت کاربران

```bash
./manage-users.sh
```

## 📖 راهنمای کامل

برای اطلاعات بیشتر، راهنمای کامل فارسی را مطالعه کنید:

👉 **[راهنمای کامل فارسی](README-FA.md)**

## 📱 کلاینت‌های پشتیبانی شده

### اندروید
- [v2rayNG](https://github.com/2dust/v2rayNG/releases) (توصیه می‌شود)
- v2rayN

### iOS
- Streisand
- FoXray

### ویندوز
- [v2rayN](https://github.com/2dust/v2rayN/releases) (توصیه می‌شود)
- Nekoray

### macOS
- V2Box
- FoXray

### لینوکس
- v2rayA
- Qv2ray

## 🛠️ مدیریت

### دستورات سرویس Xray

```bash
systemctl start xray      # شروع سرویس
systemctl stop xray       # توقف سرویس
systemctl restart xray    # ریستارت سرویس
systemctl status xray     # وضعیت سرویس
journalctl -u xray -f     # مشاهده لاگ‌های لحظه‌ای
```

### دستورات مانیتورینگ

```bash
xray-monitor              # نمایش وضعیت سیستم
htop                      # مانیتور منابع
vnstat -l                 # مانیتور ترافیک
```

### پشتیبان‌گیری

```bash
xray-backup               # پشتیبان‌گیری دستی
# پشتیبان‌گیری خودکار روزانه: هر روز ساعت 3 صبح
```

## 🔐 نکات امنیتی

1. ✅ از پورت‌های استاندارد استفاده کنید (443، 8443)
2. ✅ به‌طور منظم SNI Domain را تغییر دهید
3. ✅ تعداد کاربران را محدود کنید
4. ✅ از SSH Key به جای پسورد استفاده کنید
5. ✅ سرور را به‌روز نگه دارید
6. ✅ لاگ‌ها را به‌طور منظم بررسی کنید

## 📊 بهینه‌سازی

اسکریپت بهینه‌سازی شامل:

- ✅ فعال‌سازی TCP BBR
- ✅ بهینه‌سازی بافرهای شبکه
- ✅ تنظیم محدودیت‌های سیستم
- ✅ امن‌سازی SSH
- ✅ پیکربندی Fail2ban
- ✅ تنظیم فایروال UFW
- ✅ نصب ابزارهای مانیتورینگ
- ✅ فعال‌سازی به‌روزرسانی خودکار

## 🐛 عیب‌یابی

### سرویس شروع نمی‌شود

```bash
systemctl status xray
journalctl -u xray -n 50
/usr/local/bin/xray run -test -config /usr/local/etc/xray/config.json
```

### کلاینت متصل نمی‌شود

```bash
ufw status                           # بررسی فایروال
ss -tulpn | grep xray                # بررسی پورت
cat /root/xray-reality-info.txt      # بررسی اطلاعات
```

## 📞 پشتیبانی

- 📖 [مستندات کامل](README-FA.md)
- 🐛 [گزارش مشکل](https://github.com/hoseinmovahed88/antitahrim/issues)
- ⭐ [Xray Official](https://github.com/XTLS/Xray-core)

## ⚠️ هشدار

- این ابزار صرفاً برای دسترسی آزاد به اینترنت طراحی شده است
- از این سرویس برای فعالیت‌های غیرقانونی استفاده نکنید
- مسئولیت استفاده از این ابزار بر عهده کاربر است

## 📄 لایسنس

این پروژه تحت لایسنس MIT منتشر شده است - فایل [LICENSE](LICENSE) را برای جزئیات بیشتر مشاهده کنید.

## 🙏 تشکر

- [XTLS/Xray-core](https://github.com/XTLS/Xray-core) - پروتکل Reality
- جامعه متن‌باز - برای پشتیبانی و کمک

---

**ساخته شده با ❤️ برای اینترنت آزاد**

</div>

---

# English Documentation

## 🌟 What is Xray Reality?

Xray Reality is the most advanced anti-censorship protocol that:

- Makes your traffic look exactly like real HTTPS
- Cannot be detected by DPI (Deep Packet Inspection)
- Doesn't require a domain or SSL certificate
- Provides maximum speed with minimal overhead

## 🚀 Quick Start

### Requirements

- A VPS with Ubuntu/Debian
- Root access
- Minimum 512MB RAM

### Installation

```bash
# Connect to your server
ssh root@YOUR_SERVER_IP

# Download and run the installation script
wget https://raw.githubusercontent.com/hoseinmovahed88/antitahrim/main/install-xray-reality.sh
chmod +x install-xray-reality.sh
./install-xray-reality.sh
```

### Optimization (Recommended)

```bash
wget https://raw.githubusercontent.com/hoseinmovahed88/antitahrim/main/optimize-server.sh
chmod +x optimize-server.sh
./optimize-server.sh
```

### User Management

```bash
wget https://raw.githubusercontent.com/hoseinmovahed88/antitahrim/main/manage-users.sh
chmod +x manage-users.sh
./manage-users.sh
```

## 📱 Supported Clients

- **Android**: v2rayNG, v2rayN
- **iOS**: Streisand, FoXray
- **Windows**: v2rayN, Nekoray
- **macOS**: V2Box, FoXray
- **Linux**: v2rayA, Qv2ray

## 📚 Full Documentation

For complete documentation in Persian, see [README-FA.md](README-FA.md)

## ⚖️ License

MIT License - see [LICENSE](LICENSE) for details

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---

**Made with ❤️ for Internet Freedom**