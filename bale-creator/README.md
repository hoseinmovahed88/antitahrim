# Creator تماس بله روی سرور خودتان

این پوشه طرف آزاد روش «تماس بله» در برنامه آزاد را روی یک سرور لینوکس
بیرون از ایران راه می‌اندازد. Creator یک تماس بله می‌سازد و ترافیکی را که
گوشی شما از دل تماس می‌فرستد به اینترنت آزاد می‌رساند.

## پیش‌نیاز

- یک سرور لینوکس x64 بیرون از ایران با systemd (Ubuntu یا Debian)
- یک حساب بله، و کوکی ورود آن (پایین‌تر توضیح داده شده)

## ۱. گرفتن کوکی بله

Creator با حساب بله شما تماس می‌سازد و برای این کار کوکی ورود لازم دارد.
یکی از این دو راه:

**راه ساده‌تر، با یک کامپیوتر:** برنامه Creator دسکتاپ را از
[Releases پروژه whitelist-bypass-iran](https://github.com/kulikov0/whitelist-bypass-iran/releases)
بگیرید (ویندوز، مک یا لینوکس)، بازش کنید، **+** و بعد **Bale** را بزنید، وارد
حساب بله شوید و دکمه **Bale Cookies** را بزنید. فایل `bale-cookies.json`
ساخته می‌شود. ورود به بله از داخل ایران هم کار می‌کند.

**راه دوم، با مرورگر کامپیوتر:** وارد `web.bale.ai` شوید، DevTools را باز کنید
(F12)، بخش Application → Cookies → `https://web.bale.ai`، و مقدار
`access_token` را بردارید.

## ۲. نصب روی سرور

با root روی سرور:

```sh
curl -fsSLO https://raw.githubusercontent.com/hoseinmovahed88/antitahrim/claude/salam-2wfrye/bale-creator/install-creator.sh
sudo bash install-creator.sh
```

اسکریپت کوکی را می‌پرسد: یا محتوای `bale-cookies.json` را بچسبانید، یا یک
خط مثل `access_token=eyJ...`. بعد Creator را به‌شکل سرویس همیشگی راه می‌اندازد
و لینک تماس را نشان می‌دهد:

```
https://meet.bale.ai/i/...
```

همین لینک را در برنامه آزاد، روش «تماس بله»، بچسبانید.

## دستورهای بعدی

| دستور | کار |
|---|---|
| `sudo bash install-creator.sh link` | نمایش لینک تماس فعلی |
| `sudo bash install-creator.sh cookies` | عوض کردن کوکی وقتی منقضی شد |
| `sudo bash install-creator.sh uninstall` | حذف کامل |
| `journalctl -u azad-bale-creator -f` | دیدن گزارش زنده |

## نکته‌ها

- تا وقتی سرویس روشن است، لینک ثابت می‌ماند. اگر سرویس از نو شروع شود
  (ری‌استارت سرور، کوکی تازه) تماس تازه و لینک تازه ساخته می‌شود؛ لینک تازه را
  با دستور `link` ببینید و در برنامه عوض کنید.
- لینک را به هر کسی بدهید از سرور شما استفاده می‌کند. فقط به کسانی بدهید که
  به آنها اعتماد دارید.
- خود Creator از پروژه متن‌باز
  [whitelist-bypass-iran](https://github.com/kulikov0/whitelist-bypass-iran)
  (مجوز MIT) است؛ این اسکریپت آخرین نسخه منتشرشده آن را دریافت می‌کند.
