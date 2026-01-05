# راهنمای کامل ساخت Inbound امن در پنل 3X-UI

## 📝 تنظیمات بهینه برای VLESS Reality

### 1️⃣ اطلاعات پایه (General Settings):

```
Remark (نام):           User-Reality-001
Protocol:               VLESS
Listen IP:              0.0.0.0  (یا خالی)
Listen Port:            443
Total Flow (GB):        0  (بی‌نهایت) یا عدد دلخواه
Expiry Time:            0  (بدون انقضا) یا تاریخ دلخواه
```

### 2️⃣ تنظیمات Client (کاربر):

```
Email/ID:               user001@reality
UUID:                   [کلیک روی 🔄 برای تولید خودکار]
Flow:                   xtls-rprx-vision  ⚠️ مهم
Subscription:           ✅ (فعال)
Enable:                 ✅ (فعال)
```

### 3️⃣ تنظیمات Transport (Network):

```
Network:                tcp
Security:               reality  ⚠️ مهم

Reality Settings:
├─ Show:                ❌ (غیرفعال - برای امنیت)
├─ Dest (SNI):          www.google.com:443
│                       (یا: www.cloudflare.com:443)
│                       (یا: www.microsoft.com:443)
├─ Xver:                0
├─ Server Names:        www.google.com
│                       (باید با Dest یکی باشه)
├─ Private Key:         [کلیک روی Generate برای تولید]
├─ Public Key:          [خودکار پر میشه]
├─ Short IDs:           [کلیک روی Generate]
│                       (یا خالی بذار)
└─ Spider X:            /  (یا خالی)
```

### 4️⃣ تنظیمات Sniffing:

```
Sniffing:               ✅ فعال
Dest Override:          ✅ http
                        ✅ tls
                        ✅ quic
```

### 5️⃣ تنظیمات Allocate (پیشرفته):

```
Strategy:               always
Refresh:                5
Concurrency:            3
```

---

## 🔒 تنظیمات امنیتی (حذف/غیرفعال کنید):

### ❌ غیرفعال کنید:
- **Show در Reality**: ❌ حتماً غیرفعال
- **Stats**: اگر نیاز ندارید غیرفعال کنید
- **Allow Transparent**: ❌
- **Enable TProxy**: ❌ (مگر نیاز خاص)

### ✅ فعال کنید:
- **Enable Sniffing**: ✅
- **Block Bittorrent**: ✅ (برای جلوگیری از سوء استفاده)

---

## 📋 نمونه کانفیگ کامل JSON (برای Import):

```json
{
  "port": 443,
  "protocol": "vless",
  "settings": {
    "clients": [
      {
        "id": "UUID-AUTO-GENERATED",
        "flow": "xtls-rprx-vision",
        "email": "user001@reality"
      }
    ],
    "decryption": "none"
  },
  "streamSettings": {
    "network": "tcp",
    "security": "reality",
    "realitySettings": {
      "show": false,
      "dest": "www.google.com:443",
      "xver": 0,
      "serverNames": ["www.google.com"],
      "privateKey": "PRIVATE-KEY-HERE",
      "shortIds": ["SHORT-ID-HERE"]
    }
  },
  "sniffing": {
    "enabled": true,
    "destOverride": ["http", "tls", "quic"]
  }
}
```

---

## 🎯 چک‌لیست قبل از Save:

- [ ] Protocol = VLESS
- [ ] Flow = xtls-rprx-vision
- [ ] Security = reality
- [ ] Show = false (غیرفعال)
- [ ] Dest و ServerNames یکسان هستند
- [ ] Private/Public Key تولید شده
- [ ] Short ID تولید شده (یا خالی)
- [ ] Sniffing فعال است
- [ ] Port 443 یا پورت دلخواه باز است

---

## 🚀 بعد از ساخت:

1. روی **QR Code** کلیک کنید
2. یا روی **Export** → **Copy Link**
3. لینک را در کلاینت Import کنید
4. تست کنید

---

## 🔧 SNI های پیشنهادی (امن و سریع):

```
✅ www.google.com          (بهترین - سریع)
✅ www.cloudflare.com      (خیلی سریع)
✅ www.microsoft.com       (پایدار)
✅ www.speedtest.net       (خوب برای تست)
✅ www.yahoo.com           (جایگزین)

❌ سایت‌های فیلتر شده
❌ سایت‌هایی که HTTPS ندارند
❌ سایت‌هایی که 403 برمی‌گردانند
```

---

## ⚠️ نکات امنیتی مهم:

1. **هرگز این‌ها را فعال نکنید:**
   - WebSocket بدون TLS
   - Allow Transparent
   - Debug Mode در production

2. **حتماً این‌ها را تنظیم کنید:**
   - Show = false
   - Sniffing = true
   - Block torrent = true

3. **برای چند کاربر:**
   - هر کاربر یک UUID جداگانه
   - Email/ID مختلف برای هر کاربر
   - Remark واضح (مثل User-1, User-2)

4. **پورت‌های امن:**
   - 443 (HTTPS - بهترین)
   - 8443 (جایگزین)
   - 2053, 2083, 2096 (Cloudflare ports)

---

## 📱 تست اتصال:

بعد از ساخت:
1. QR Code رو اسکن کن
2. یا لینک رو کپی کن
3. توی v2rayNG یا v2rayN import کن
4. Connect کن
5. برو به ip.gs و IP سرور رو چک کن

---

**موفق باشید! 🚀**
