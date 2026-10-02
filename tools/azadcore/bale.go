package azadcore

import (
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"

	"github.com/pion/webrtc/v4"

	"whitelist-bypass-iran/relay/common"
	joiner "whitelist-bypass-iran/relay/pion/headless-joiner-common"
	"whitelist-bypass-iran/relay/tunnel"
)

// BaleListener رویدادهای تونل بله را به سمت اندروید می‌رساند.
//
// gomobile این رابط را به یک interface جاوا تبدیل می‌کند که کاتلین
// پیاده‌اش می‌کند.
type BaleListener interface {
	// OnLog یک خط از گزارش جوینر.
	OnLog(msg string)
	// OnStatus یکی از READY، CONNECTING، TUNNEL_CONNECTED، TUNNEL_LOST
	// یا ERROR:<پیام>.
	OnStatus(status string)
}

var baleState struct {
	sync.Mutex
	joiner   *joiner.BaleHeadlessJoiner
	bridge   *tunnel.RelayBridge
	listener BaleListener
	stopped  bool
}

type baleStatus struct{ listener BaleListener }

func (s baleStatus) EmitStatus(status string)   { s.listener.OnStatus(status) }
func (s baleStatus) EmitStatusError(msg string) { s.listener.OnStatus(common.StatusError + ":" + msg) }

// روی اندروید ۱۱ به بعد، Pion نمی‌تواند رابط‌های شبکه را از راه netlink
// بشمارد و بدون این تنظیم بی‌صدا هیچ نامزد ICE پیدا نمی‌کند. همان کاری
// که برنامه اندروید خود پروژه بالادستی انجام می‌دهد.
type androidPC struct{}

func (androidPC) ConfigureSettingEngine(se *webrtc.SettingEngine) {
	se.SetNet(&common.AndroidNet{})
}

// StartBale به تماس بله‌ای که لینکش داده شده می‌پیوندد و وقتی تونل از دل
// تماس برقرار شد، یک پروکسی SOCKS5 روی 127.0.0.1:socksPort باز می‌کند.
//
// بازگشت این تابع یعنی کار شروع شد، نه اینکه تونل برقرار است؛ برقراری را
// OnStatus با TUNNEL_CONNECTED خبر می‌دهد.
//
// tunnelMode یکی از "vp8" (پیش‌فرض، سریع‌تر) یا "dc" است.
func StartBale(joinLink string, socksPort int, tunnelMode string, listener BaleListener) (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("تونل بله از کار افتاد: %v", r)
		}
	}()

	if listener == nil {
		return errors.New("شنونده رویدادها داده نشده")
	}
	joinLink = strings.TrimSpace(joinLink)
	if !strings.Contains(joinLink, "meet.bale.ai") {
		return errors.New("این لینک تماس بله نیست؛ باید شبیه https://meet.bale.ai/i/... باشد")
	}
	if socksPort <= 0 || socksPort > 65535 {
		return fmt.Errorf("پورت SOCKS نامعتبر: %d", socksPort)
	}
	if tunnelMode != "dc" {
		tunnelMode = "vp8"
	}

	StopBale()

	logFn := func(format string, args ...any) {
		listener.OnLog(fmt.Sprintf(format, args...))
	}
	// نام meet.bale.ai با DNS خود گوشی حل می‌شود. بله سرویس داخلی و در
	// فهرست سفید است، پس DNS اپراتور جواب درست می‌دهد.
	resolve := func(host string) (string, error) {
		ips, err := net.LookupIP(host)
		if err != nil {
			return "", err
		}
		for _, ip := range ips {
			if v4 := ip.To4(); v4 != nil {
				return v4.String(), nil
			}
		}
		if len(ips) > 0 {
			return ips[0].String(), nil
		}
		return "", fmt.Errorf("نامی برای %s پیدا نشد", host)
	}

	j := joiner.NewBaleHeadlessJoiner(logFn, resolve, baleStatus{listener}, androidPC{})
	j.OnConnected = func(tun tunnel.DataTunnel) {
		baleState.Lock()
		stopped := baleState.stopped
		baleState.Unlock()
		if stopped {
			return
		}

		readBuf := common.VP8BufSize
		if _, ok := tun.(*tunnel.DCTunnel); ok {
			readBuf = common.DCSocksReadBuf
		}
		bridge := tunnel.NewRelayBridge(tun, "joiner", readBuf, logFn)
		bridge.MarkReady()

		// پل نگه داشته می‌شود تا StopBale بتواند ببنددش؛ بستن پل
		// شنونده SOCKS را هم می‌بندد و پورت برای اتصال بعدی آزاد می‌شود.
		baleState.Lock()
		if baleState.stopped {
			baleState.Unlock()
			bridge.Close()
			return
		}
		previous := baleState.bridge
		baleState.bridge = bridge
		baleState.Unlock()
		if previous != nil {
			previous.Close()
		}

		addr := "127.0.0.1:" + strconv.Itoa(socksPort)
		go func() {
			if err := bridge.ListenSOCKS(addr); err != nil {
				baleState.Lock()
				stopped := baleState.stopped
				baleState.Unlock()
				if !stopped {
					listener.OnStatus(common.StatusError + ":socks: " + err.Error())
				}
			}
		}()
	}

	baleState.Lock()
	baleState.joiner = j
	baleState.listener = listener
	baleState.stopped = false
	baleState.Unlock()

	params := fmt.Sprintf(
		`{"joinLink":%q,"displayName":%q,"tunnelMode":%q,"resources":"default"}`,
		joinLink, "Azad", tunnelMode,
	)
	go func() {
		defer func() {
			if r := recover(); r != nil {
				listener.OnStatus(fmt.Sprintf("%s:panic: %v", common.StatusError, r))
			}
		}()
		j.RunWithParams(params)
	}()
	return nil
}

// StopBale تماس را ترک و پروکسی را می‌بندد. فراخوانی چندباره‌اش بی‌ضرر است.
func StopBale() {
	baleState.Lock()
	baleState.stopped = true
	j := baleState.joiner
	bridge := baleState.bridge
	baleState.joiner = nil
	baleState.bridge = nil
	baleState.listener = nil
	baleState.Unlock()

	if bridge != nil {
		bridge.Close()
	}
	if j != nil {
		j.Close()
	}
}
