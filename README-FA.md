# 🚀 Xray Reality - راهنمای کامل نصب و استفاده

## 📖 فهرست مطالب
- [مقدمه](#مقدمه)
- [نصب سرور](#نصب-سرور)
- [مدیریت کاربران](#مدیریت-کاربران)
- [کلاینت‌ها](#کلاینت‌ها)
- [پلی‌استیشن (PS4/PS5)](#پلیاستیشن)
- [نکات امنیتی](#نکات-امنیتی)
- [عیب‌یابی](#عیب‌یابی)

---

## 🔍 مقدمه

**Xray Reality** جدیدترین و قوی‌ترین پروتکل ضد فیلترینگ است که:

✅ **غیرقابل تشخیص**: ترافیک شما دقیقاً شبیه HTTPS واقعی است  
✅ **امن**: رمزنگاری پیشرفته با کلیدهای خصوصی  
✅ **سریع**: بدون overhead اضافی، سرعت بالا  
✅ **مقاوم**: در برابر DPI و فیلترینگ عمیق مقاوم است  
✅ **بدون دامنه**: نیازی به خرید دامنه و SSL نیست  

---

## 🖥️ نصب سرور

### پیش‌نیازها

- یک سرور مجازی (VPS) با سیستم عامل Ubuntu یا Debian
- دسترسی root به سرور
- حداقل 512MB رم
- اتصال اینترنت

### مراحل نصب

#### 1. اتصال به سرور

از طریق SSH به سرور خود متصل شوید:

```bash
ssh root@YOUR_SERVER_IP
```

#### 2. دانلود فایل‌ها از Repository خصوصی

چون این repository خصوصی است، از یکی از روش‌های زیر استفاده کنید:

##### روش 1: Clone کردن کل پروژه (توصیه می‌شود)

```bash
# نصب git (اگر نصب نیست)
apt update && apt install -y git

# Clone با HTTPS (نیاز به Personal Access Token)
git clone https://github.com/hoseinmovahed88/antitahrim.git
cd antitahrim
chmod +x *.sh

# Git از شما username و password می‌خواهد:
# Username: hoseinmovahed88
# Password: [Personal Access Token شما]
```

##### روش 2: دانلود مستقیم با curl

```bash
# متغیر TOKEN را تنظیم کنید (یکبار)
export TOKEN="ghp_YOUR_TOKEN_HERE"

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
```

##### روش 3: ایجاد Personal Access Token

1. به GitHub بروید: https://github.com/settings/tokens
2. روی "Generate new token" کلیک کنید
3. نام بدهید (مثلاً "VPS Access")
4. دسترسی‌ها را انتخاب کنید: `repo` (full control)
5. روی "Generate token" کلیک کنید
6. توکن را کپی کنید و در جای امنی ذخیره کنید

#### 3. اجرای اسکریپت نصب

##### نصب Xray Reality

```bash
# اگر clone کردید
cd antitahrim
./install-xray-reality.sh

# یا اگر فقط دانلود کردید
./install-xray-reality.sh
```

##### نصب پنل وب (اختیاری)

برای مدیریت راحت‌تر از طریق مرورگر:

```bash
./install-panel.sh
```

پس از نصب، پنل در آدرس زیر در دسترس خواهد بود:
- آدرس: `http://SERVER_IP:2053/RANDOM_PATH`
- نام کاربری و رمز عبور پس از نصب نمایش داده می‌شود

> 📖 **راهنمای کامل پنل**: برای تنظیمات دقیق، نکات امنیتی و پیکربندی Reality در پنل، حتماً [راهنمای پنل](PANEL-GUIDE.md) را مطالعه کنید.

##### بهینه‌سازی سرعت

برای بهترین عملکرد:

```bash
./optimize-speed.sh
```

این اسکریپت:
- TCP BBR را فعال می‌کند (بهبود سرعت در شبکه‌های پرتاخیر)
- بافرهای شبکه را افزایش می‌دهد
- تنظیمات سیستمی را بهینه می‌کند

#### 4. وارد کردن اطلاعات

اسکریپت از شما چند سوال می‌پرسد:

- **پورت**: پیشنهاد می‌شود 443 یا یک پورت تصادفی بین 1000-65535
- **SNI Domain**: یک دامنه معتبر مثل:
  - `www.google.com`
  - `www.cloudflare.com`
  - `www.microsoft.com`
  - `www.speedtest.net`

> 💡 **نکته**: SNI Domain باید یک سایت واقعی و HTTPS باشد.

#### 4. دریافت اطلاعات اتصال

پس از نصب موفق، اطلاعات کامل اتصال نمایش داده می‌شود:

```
آدرس سرور: YOUR_SERVER_IP
پورت: 443
UUID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
Public Key: xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
Short ID: xxxxxxxxxxxxxxxx
SNI: www.google.com
```

همچنین یک **لینک VLESS** و **QR Code** دریافت می‌کنید که می‌توانید مستقیماً در کلاینت استفاده کنید.

---

## 👥 مدیریت کاربران

برای مدیریت کاربران (اضافه، حذف، نمایش لینک) از اسکریپت مدیریتی استفاده کنید:

```bash
# دانلود اسکریپت مدیریت
wget https://raw.githubusercontent.com/hoseinmovahed88/antitahrim/main/manage-users.sh

# اجازه اجرا
chmod +x manage-users.sh

# اجرا
./manage-users.sh
```

### امکانات اسکریپت مدیریت:

1. **لیست کاربران**: مشاهده تمام کاربران فعال
2. **اضافه کردن کاربر**: ایجاد UUID جدید و لینک اتصال
3. **حذف کاربر**: حذف یک کاربر خاص
4. **نمایش اطلاعات کاربر**: دریافت لینک و QR Code
5. **وضعیت سرویس**: بررسی وضعیت Xray
6. **تنظیمات پیشرفته**:
   - تغییر پورت
   - تغییر SNI Domain
   - تولید کلیدهای جدید
   - پشتیبان‌گیری از کانفیگ

### اسکریپت‌های کاربردی دیگر:

#### نصب پنل وب 3X-UI

برای مدیریت گرافیکی از طریق مرورگر:

```bash
./install-panel.sh
```

> 📖 برای راهنمای کامل تنظیمات پنل، به [راهنمای پنل](PANEL-GUIDE.md) مراجعه کنید.

#### بهینه‌سازی سرعت شبکه

```bash
./optimize-speed.sh
```

این اسکریپت سرعت اتصال را با فعال‌سازی TCP BBR و تنظیم بافرهای شبکه بهبود می‌دهد.

#### رفع مشکل SSH

```bash
./fix-ssh.sh
```

اگر دسترسی SSH خود را از دست دادید، این اسکریپت تنظیمات را بازیابی می‌کند.

---

## 📱 کلاینت‌ها

### اندروید

#### 1. **v2rayNG** (توصیه می‌شود)
- دانلود: [GitHub](https://github.com/2dust/v2rayNG/releases)
- نحوه استفاده:
  1. نصب برنامه
  2. روی `+` بزنید
  3. "Import config from clipboard" یا اسکن QR Code
  4. روی کانفیگ کلیک کنید و Connect بزنید

#### 2. **v2rayN (کلاینت دسکتاپ Android)**
- مشابه v2rayNG

### iOS

#### 1. **Streisand** (رایگان)
- دانلود از App Store
- از طریق لینک VLESS یا QR Code وارد کنید

#### 2. **FoXray**
- قابلیت‌های پیشرفته‌تر
- پشتیبانی کامل از Reality

### ویندوز

#### 1. **v2rayN** (توصیه می‌شود)
- دانلود: [GitHub](https://github.com/2dust/v2rayN/releases)
- نصب .NET 6.0 Runtime لازم است
- نحوه استفاده:
  1. اجرای برنامه
  2. Servers → Add server via clipboard
  3. پیست کردن لینک VLESS

#### 2. **Nekoray**
- رابط کاربری مدرن‌تر
- دانلود: [GitHub](https://github.com/MatsuriDayo/nekoray/releases)

### macOS

#### 1. **V2Box**
- دانلود از App Store

#### 2. **FoXray**
- پشتیبانی کامل از Reality

### لینوکس

#### 1. **v2rayA** (توصیه می‌شود)
- نصب:
```bash
# Ubuntu/Debian
wget -qO - https://apt.v2raya.org/key/public-key.asc | sudo apt-key add -
sudo add-apt-repository 'deb https://apt.v2raya.org/ v2raya main'
sudo apt update
sudo apt install v2raya
```

#### 2. **Qv2ray**
- رابط گرافیکی
- دانلود: [GitHub](https://github.com/Qv2ray/Qv2ray/releases)

---

## 🎮 پلی‌استیشن

کنسول پلی‌استیشن با دو مانع جداگانه روبه‌روست: فیلترینگ اپراتور ایران، و
تحریم آی‌پی از طرف خود Sony. فقط عوض کردن DNS مانع دوم را برنمی‌دارد.

برای همین یک ابزار جداگانه در پوشه `playstation/` هست که یک دستگاه لینوکسی
کوچک در خانه شما را به دروازه کنسول تبدیل می‌کند. این دروازه فقط دامنه‌های
Sony و ناشران بازی را از تونل رد می‌کند و ترافیک UDP خود بازی را دست نمی‌زند،
پس **NAT Type و پینگ شما خراب نمی‌شود.**

```bash
# روی یک رزبری‌پای یا مینی‌پی‌سی در همان شبکه کنسول
git clone https://github.com/hoseinmovahed88/antitahrim.git
cd antitahrim/playstation
sudo ./install-ps-gateway.sh
```

بعد از نصب روی کنسول DNS را روی آی‌پی دروازه بگذارید و پروکسی را روی همان
آی‌پی و پورت `8888` فعال کنید.

برای تست کامل و عیب‌یابی:

```bash
sudo ps-check
```

📖 راهنمای کامل و گام‌به‌گام: [PLAYSTATION-GUIDE.md](PLAYSTATION-GUIDE.md)

---

## 🔐 نکات امنیتی

### 1. تغییر پورت SSH

```bash
# ویرایش فایل کانفیگ SSH
nano /etc/ssh/sshd_config

# تغییر پورت (مثلاً به 2222)
Port 2222

# ریستارت سرویس
systemctl restart sshd
```

### 2. استفاده از SSH Key به جای پسورد

```bash
# روی کامپیوتر شخصی
ssh-keygen -t ed25519

# کپی کلید به سرور
ssh-copy-id -i ~/.ssh/id_ed25519.pub root@YOUR_SERVER_IP

# غیرفعال کردن ورود با پسورد
nano /etc/ssh/sshd_config
# تغییر به: PasswordAuthentication no
systemctl restart sshd
```

### 3. فعال‌سازی Fail2ban (در اسکریپت نصب موجود است)

```bash
# بررسی وضعیت
systemctl status fail2ban

# مشاهده IP های بلاک شده
fail2ban-client status sshd
```

### 4. به‌روزرسانی منظم سیستم

```bash
# هر هفته اجرا کنید
apt update && apt upgrade -y
```

### 5. تغییر منظم SNI Domain

برای افزایش امنیت، هر چند ماه SNI را تغییر دهید:

```bash
./manage-users.sh
# انتخاب گزینه "6. تنظیمات پیشرفته"
# سپس "2. تغییر SNI Domain"
```

### 6. استفاده از پورت‌های استاندارد

برای کاهش شک، از پورت‌های استاندارد HTTPS استفاده کنید:
- `443` (HTTPS)
- `8443` (HTTPS جایگزین)
- `2053`, `2083`, `2087`, `2096` (پورت‌های Cloudflare)

### 7. محدود کردن تعداد کاربران

هر کاربر اضافی = خطر بیشتر. تنها به افراد مورد اعتماد دسترسی دهید.

---

## 🔧 عیب‌یابی

### سرویس Xray اجرا نمی‌شود

```bash
# بررسی وضعیت
systemctl status xray

# مشاهده لاگ‌ها
journalctl -u xray -n 50 --no-pager

# بررسی کانفیگ
/usr/local/bin/xray run -test -config /usr/local/etc/xray/config.json
```

### کلاینت متصل نمی‌شود

1. **بررسی فایروال سرور:**
```bash
ufw status
# باید پورت شما ALLOW باشد
```

2. **بررسی پورت:**
```bash
ss -tulpn | grep xray
# باید پورت در حال گوش دادن باشد
```

3. **بررسی اطلاعات اتصال:**
```bash
cat /root/xray-reality-info.txt
# مطمئن شوید اطلاعات را صحیح کپی کرده‌اید
```

4. **تست اتصال:**
```bash
curl -I https://YOUR_SERVER_IP:YOUR_PORT
# باید خطای SSL دریافت کنید (طبیعی است)
```

### سرعت پایین

1. **بررسی CPU و RAM:**
```bash
top
```

2. **تغییر SNI به سایتی سریع‌تر:**
```bash
./manage-users.sh
# تغییر SNI Domain به www.cloudflare.com
```

3. **بهینه‌سازی TCP:**
```bash
# این تنظیمات در اسکریپت نصب اعمال می‌شوند
sysctl -p
```

### پورت بسته است

```bash
# بررسی فایروال
ufw allow YOUR_PORT/tcp

# اگر سرور در پشت فایروال دیگری است:
# - AWS: Security Groups را بررسی کنید
# - OVH: Firewall در پنل را چک کنید
```

### سرور هک شده یا مشکوک است

```bash
# بررسی اتصالات فعال
ss -tunap

# بررسی فرآیندهای مشکوک
ps aux | grep -v grep

# بررسی لاگ SSH
tail -f /var/log/auth.log

# اگر مشکوک بود، سرور را از نو نصب کنید
```

---

## 📊 دستورات مفید

### مدیریت سرویس Xray

```bash
# شروع سرویس
systemctl start xray

# توقف سرویس
systemctl stop xray

# ریستارت سرویس
systemctl restart xray

# وضعیت سرویس
systemctl status xray

# فعال‌سازی در بوت
systemctl enable xray

# غیرفعال‌سازی در بوت
systemctl disable xray

# مشاهده لاگ‌های لحظه‌ای
journalctl -u xray -f
```

### بررسی مصرف منابع

```bash
# مصرف CPU و RAM
htop

# مصرف ترافیک
vnstat -l

# مصرف دیسک
df -h

# فضای استفاده شده توسط لاگ‌ها
du -sh /var/log/xray/
```

### پاکسازی و نگهداری

```bash
# پاکسازی لاگ‌های قدیمی
truncate -s 0 /var/log/xray/access.log
truncate -s 0 /var/log/xray/error.log

# پاکسازی لاگ‌های سیستم
journalctl --vacuum-time=7d

# حذف بسته‌های غیرضروری
apt autoremove -y
apt autoclean
```

---

## 🆘 پشتیبانی

اگر مشکلی داشتید:

1. **مستندات رسمی Xray**: https://xtls.github.io/
2. **GitHub Issues**: مشکل خود را در Issues گزارش دهید
3. **تلگرام**: کانال‌های فارسی ضد فیلتر

---

## ⚠️ هشدارها

1. ⚠️ این ابزار فقط برای دسترسی آزاد به اینترنت است
2. ⚠️ از این سرویس برای فعالیت‌های غیرقانونی استفاده نکنید
3. ⚠️ اطلاعات سرور خود را با کسی به اشتراک نگذارید
4. ⚠️ به‌طور منظم از سرور خود پشتیبان بگیرید
5. ⚠️ اگر سرور مشکوک شد، فوراً آن را تغییر دهید

---

## 📝 لایسنس

این پروژه تحت لایسنس MIT منتشر شده است.

---

## ❤️ حمایت

اگر این پروژه برای شما مفید بود:
- ⭐ به ریپازیتوری استار بدهید
- 🔄 آن را به دوستان خود معرفی کنید
- 🐛 باگ‌ها را گزارش دهید
- 💡 پیشنهادات خود را ارسال کنید

---

**ساخته شده با ❤️ برای اینترنت آزاد**
