#!/usr/bin/env bash
# نصب Creator تماس بله روی یک سرور لینوکس بیرون از ایران.
#
# Creator طرف آزاد تونل «تماس بله» در برنامه آزاد است: یک تماس بله می‌سازد،
# لینکش را می‌دهد، و ترافیکی را که گوشی داخل ایران از دل تماس می‌فرستد به
# اینترنت آزاد می‌رساند. خود Creator از پروژه متن‌باز whitelist-bypass-iran
# (مجوز MIT) می‌آید؛ این اسکریپت فقط نصبش می‌کند و سرویس همیشگی می‌سازد.
#
# استفاده (با root):
#   bash install-creator.sh              نصب و راه‌اندازی
#   bash install-creator.sh link         نمایش لینک تماس فعلی
#   bash install-creator.sh cookies      عوض کردن کوکی بله (وقتی منقضی شد)
#   bash install-creator.sh install FILE  نصب با کوکی از یک فایل
#   bash install-creator.sh cookies FILE  عوض کردن کوکی از یک فایل
#   bash install-creator.sh uninstall    حذف کامل
set -euo pipefail

BIN=/usr/local/bin/headless-bale-creator
CONF_DIR=/etc/azad-bale
COOKIE_FILE=$CONF_DIR/bale-cookies.json
UNIT=/etc/systemd/system/azad-bale-creator.service
LINK_FILE=/var/lib/azad-bale/call.txt
SERVICE=azad-bale-creator
RELEASE=https://github.com/kulikov0/whitelist-bypass-iran/releases/latest/download

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
die()  { printf '\n\033[31mخطا: %s\033[0m\n' "$*" >&2; exit 1; }

need_root() { [ "$(id -u)" -eq 0 ] || die "با root اجرا کنید: sudo bash $0 $*"; }

current_link() {
	[ -s "$LINK_FILE" ] && tail -n 1 "$LINK_FILE" || true
}

# کوکی را به هر شکلی که معمولاً کپی می‌شود می‌پذیرد:
#   - فایل JSON که دکمه «Bale Cookies» در برنامه Creator دسکتاپ می‌سازد
#   - یک خط مثل access_token=eyJ...  (چند کوکی با ; جدا)
#   - سطر جدول DevTools که نام و مقدار را با Tab یا فاصله جدا می‌کند
#   - یا فقط خود توکن، که با eyJ شروع می‌شود
# اگر آرگومان اول مسیر یک فایل باشد، از همان فایل خوانده می‌شود. برای کوکی‌های
# خیلی بلند بهتر است، چون ترمینال خط‌های بلندتر از حدود ۴۰۰۰ نویسه را می‌بُرد.
read_cookies() {
	local input="" line
	if [ -n "${1:-}" ] && [ -f "$1" ]; then
		input="$(cat "$1")"
	else
		say "کوکی حساب بله را وارد کنید"
		cat <<'TXT'
هر کدام از این‌ها پذیرفته می‌شود:
  ۱. محتوای فایل bale-cookies.json که برنامه Creator دسکتاپ با دکمه
     «Bale Cookies» می‌سازد.
  ۲. خط  access_token=eyJ...
  ۳. فقط خود توکن که با eyJ شروع می‌شود (از DevTools مرورگر، بخش
     Application → Cookies → web.bale.ai، ستون Value ردیف access_token).

متن را بچسبانید و بعد یک خط خالی بزنید (Enter دو بار):
TXT
		while IFS= read -r line; do
			[ -z "$line" ] && [ -n "$input" ] && break
			input+="$line"$'\n'
		done
	fi
	[ -n "$(printf '%s' "$input" | tr -d '[:space:]')" ] || die "چیزی وارد نشد"

	mkdir -p "$CONF_DIR"
	chmod 700 "$CONF_DIR"
	local tmp="$COOKIE_FILE.new"
	# فایل موقت؛ فقط اگر معتبر بود جای فایل اصلی را می‌گیرد، تا ورودی خراب
	# دفعه بعد بی‌سؤال دوباره استفاده نشود
	if ! printf '%s' "$input" | python3 -c "$COOKIE_PARSER" > "$tmp"; then
		rm -f "$tmp"
		die "کوکی access_token در آنچه وارد شد پیدا نشد. یا خط access_token=... را بچسبانید، یا فقط مقدار توکن را که با eyJ شروع می‌شود."
	fi
	chmod 600 "$tmp"
	mv "$tmp" "$COOKIE_FILE"
	echo "کوکی ذخیره شد: $COOKIE_FILE"
}

# هر شکل ورودی را به همان آرایه JSON تبدیل می‌کند که Creator می‌خواند.
# اگر access_token در آن نباشد با کد ۱ خارج می‌شود.
COOKIE_PARSER='
import json, re, sys
raw = sys.stdin.read().strip()
pairs = []

def add(name, value):
    name, value = name.strip().strip("\"\x27:"), value.strip().strip("\"\x27")
    if name and value:
        pairs.append({"name": name, "value": value})

try:
    data = json.loads(raw)
    if isinstance(data, dict):
        data = [data] if "name" in data else [{"name": k, "value": v} for k, v in data.items()]
    for c in data:
        add(str(c.get("name", "")), str(c.get("value", "")))
except Exception:
    for part in re.split(r"[;\n]+", raw):
        part = part.strip()
        if not part:
            continue
        if "=" in part and not part.startswith("eyJ"):
            name, value = part.split("=", 1)
            add(name, value)
            continue
        cols = part.split()
        if len(cols) >= 2 and not cols[0].startswith("eyJ"):
            add(cols[0], cols[1])
        elif part.startswith("eyJ"):
            add("access_token", cols[0])

if not any(p["name"] == "access_token" for p in pairs):
    sys.exit(1)
json.dump(pairs, sys.stdout)
'

install_binary() {
	local arch
	case "$(uname -m)" in
		x86_64|amd64) arch=x64 ;;
		i386|i686)    arch=ia32 ;;
		*) die "معماری $(uname -m) پشتیبانی نمی‌شود؛ پروژه بالادستی فقط نسخه x64 و ia32 می‌دهد" ;;
	esac
	say "دریافت Creator ($arch)"
	curl -fL --retry 3 -o "$BIN.tmp" "$RELEASE/headless-bale-creator-linux-$arch" \
		|| die "دریافت از گیت‌هاب ناموفق بود"
	chmod +x "$BIN.tmp"
	mv "$BIN.tmp" "$BIN"
}

install_service() {
	local mem resources
	mem=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
	if   [ "$mem" -le 600 ];  then resources=moderate
	elif [ "$mem" -ge 1900 ]; then resources=unlimited
	else                           resources=default
	fi

	id azadbale >/dev/null 2>&1 || useradd -r -s /usr/sbin/nologin azadbale
	chown -R azadbale "$CONF_DIR"
	mkdir -p "$(dirname "$LINK_FILE")"
	chown azadbale "$(dirname "$LINK_FILE")"

	cat > "$UNIT" <<UNITEOF
[Unit]
Description=Azad - Bale call Creator
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=azadbale
ExecStart=$BIN --cookies $COOKIE_FILE --write-file $LINK_FILE --resources $resources
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
UNITEOF
	systemctl daemon-reload
	systemctl enable "$SERVICE" >/dev/null
	systemctl restart "$SERVICE"
	echo "سرویس با حالت منابع «$resources» راه افتاد (حافظه سرور: ${mem}MB)"
}

wait_for_link() {
	say "منتظر ساخت تماس…"
	local before after i
	before="$(current_link)"
	for i in $(seq 1 60); do
		after="$(current_link)"
		if [ -n "$after" ] && [ "$after" != "$before" ]; then
			say "تماس ساخته شد. این لینک را در برنامه آزاد، روش «تماس بله»، بچسبانید:"
			printf '\n    %s\n\n' "$after"
			echo "هر وقت لازم شد دوباره ببینیدش:  bash $0 link"
			return 0
		fi
		if ! systemctl is-active --quiet "$SERVICE"; then
			journalctl -u "$SERVICE" -n 15 --no-pager
			die "سرویس متوقف شد. اگر خطا درباره کوکی است: bash $0 cookies"
		fi
		sleep 2
	done
	journalctl -u "$SERVICE" -n 20 --no-pager
	die "تماس در دو دقیقه ساخته نشد. گزارش بالا را ببینید."
}

case "${1:-install}" in
	install)
		need_root "$@"
		command -v systemctl >/dev/null || die "systemd پیدا نشد"
		command -v curl >/dev/null || apt-get install -y curl >/dev/null
		command -v python3 >/dev/null || apt-get install -y python3 >/dev/null
		install_binary
		# فایل کوکی قبلی فقط اگر واقعاً access_token داشته باشد نگه داشته
		# می‌شود؛ نسخه قبلی این اسکریپت ورودی نامعتبر را هم ذخیره می‌کرد
		grep -q '"access_token"' "$COOKIE_FILE" 2>/dev/null || read_cookies "${2:-}"
		install_service
		wait_for_link
		;;
	link)
		l="$(current_link)"
		[ -n "$l" ] && echo "$l" || die "هنوز لینکی نیست؛ وضعیت: systemctl status $SERVICE"
		;;
	cookies)
		need_root "$@"
		read_cookies "${2:-}"
		chown -R azadbale "$CONF_DIR"
		systemctl restart "$SERVICE"
		wait_for_link
		;;
	uninstall)
		need_root "$@"
		systemctl disable --now "$SERVICE" 2>/dev/null || true
		rm -f "$UNIT" "$BIN"
		rm -rf "$CONF_DIR" "$(dirname "$LINK_FILE")"
		systemctl daemon-reload
		echo "حذف شد."
		;;
	*)
		die "دستور ناشناخته: $1 (install | link | cookies | uninstall)"
		;;
esac
