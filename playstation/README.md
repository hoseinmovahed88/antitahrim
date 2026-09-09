# 🎮 PlayStation Gateway

<div dir="rtl">

ابزار باز کردن پلی‌استیشن از داخل ایران.

این پوشه روی **سرور خارج نصب نمی‌شود.** روی یک دستگاه لینوکسی کوچک در خانه
شما نصب می‌شود که به همان مودم کنسول وصل است: رزبری‌پای، مینی‌پی‌سی، یا لپ‌تاپ قدیمی.

## نصب

```bash
sudo ./install-ps-gateway.sh
```

لینک `vless://` سرور خارج را که موقع نصب `install-xray-reality-en.sh` گرفتید
وارد کنید.

## چه می‌کند

ترافیک کنسول را تفکیک می‌کند. دامنه‌های Sony و ناشران بازی از تونل Reality
رد می‌شوند، بقیه ترافیک مستقیم می‌رود. ترافیک UDP بازی هیچ‌وقت تونل نمی‌شود،
پس NAT Type و پینگ دست‌نخورده می‌ماند.

## فایل‌ها

| فایل | کار |
|------|-----|
| `install-ps-gateway.sh` | نصب کامل دروازه |
| `manage-ps.sh` | منوی مدیریت (`ps-manage`) |
| `ps-mode.sh` | تغییر حالت پروکسی/دروازه (`ps-mode`) |
| `check-psn.sh` | تست و عیب‌یابی (`ps-check`) |
| `ps-genconfig.sh` | بازسازی کانفیگ Xray (`ps-genconfig`) |
| `domains/psn.list` | دامنه‌های Sony |
| `domains/games.list` | دامنه‌های ناشران بازی |

📖 راهنمای کامل: [../PLAYSTATION-GUIDE.md](../PLAYSTATION-GUIDE.md)

</div>
