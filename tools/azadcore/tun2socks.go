// Package azadcore هستهٔ شبکه برنامه است: یک پروکسی Xray در فرایند، و پلی
// بین دستگاه tun اندروید و آن پروکسی.
//
// چرا لازم است: Xray یک پروکسی SOCKS روی گوشی باز می‌کند، ولی VpnService
// اندروید بسته‌های خام IP می‌دهد. این بسته‌ها باید به اتصال‌های TCP و UDP
// ترجمه و به آن پروکسی سپرده شوند. این کار یک پشته شبکه در فضای کاربر
// می‌خواهد که اینجا از gVisor می‌آید.
//
// این بسته با gomobile به یک کتابخانه اندروید تبدیل می‌شود:
//
//	gomobile bind -target=android/arm64,android/arm -o azadcore.aar .
package azadcore

import (
	"errors"
	"fmt"
	"strconv"
	"sync"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/device"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	"github.com/xjasonlyu/tun2socks/v2/proxy"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"

	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

var (
	bridgeMu     sync.Mutex
	bridgeDevice device.Device
	bridgeStack  *stack.Stack
)

// Start ترجمه بسته‌ها را روی توصیف‌گر فایل دستگاه tun آغاز می‌کند.
//
// fd همان چیزی است که VpnService.establish برمی‌گرداند. مالکیتش به این لایه
// می‌رسد و هنگام Stop بسته می‌شود.
//
// socksPort پورت پروکسی SOCKS محلی است که Xray باز کرده.
//
// نکته‌ای که این تابع را طولانی‌تر از انتظار کرده: بسته engine در tun2socks
// یک تابع Start بدون خطا دارد که در صورت شکست log.Fatalf صدا می‌زند، و آن
// یعنی os.Exit. روی اندروید کل فرایند بی‌صدا کشته می‌شود؛ نه استثنایی، نه
// ردی، نه گزارشی. به جایش همان کارهایی که engine می‌کند اینجا مستقیم و با
// خطای برگشتی انجام می‌شود.
func Start(fd int, socksPort int, mtu int) (err error) {
	// هر panic در لایه‌های پایین هم باید به خطا تبدیل شود نه به مرگ فرایند
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("پل tun2socks از کار افتاد: %v", r)
		}
	}()

	bridgeMu.Lock()
	defer bridgeMu.Unlock()

	if bridgeStack != nil {
		return errors.New("پل tun2socks از قبل در حال اجراست")
	}
	if fd <= 0 {
		return fmt.Errorf("توصیف‌گر فایل نامعتبر: %d", fd)
	}
	if socksPort <= 0 || socksPort > 65535 {
		return fmt.Errorf("پورت SOCKS نامعتبر: %d", socksPort)
	}
	if mtu <= 0 {
		mtu = 1500
	}

	dialer, err := proxy.NewSocks5("127.0.0.1:"+strconv.Itoa(socksPort), "", "")
	if err != nil {
		return fmt.Errorf("پروکسی SOCKS ساخته نشد: %w", err)
	}
	tunnel.T().SetDialer(dialer)
	tunnel.T().SetUDPTimeout(60 * time.Second)

	dev, err := fdbased.Open(strconv.Itoa(fd), uint32(mtu), 0)
	if err != nil {
		return fmt.Errorf("دستگاه tun باز نشد: %w", err)
	}

	netStack, err := core.CreateStack(&core.Config{
		LinkEndpoint:     dev,
		TransportHandler: tunnel.T(),
	})
	if err != nil {
		dev.Close()
		return fmt.Errorf("پشته شبکه ساخته نشد: %w", err)
	}

	bridgeDevice = dev
	bridgeStack = netStack
	return nil
}

// Stop ترجمه را متوقف می‌کند. فراخوانی چندباره‌اش بی‌ضرر است.
func Stop() {
	bridgeMu.Lock()
	defer bridgeMu.Unlock()

	if bridgeDevice != nil {
		bridgeDevice.Close()
		bridgeDevice = nil
	}
	if bridgeStack != nil {
		bridgeStack.Close()
		bridgeStack.Wait()
		bridgeStack = nil
	}
}

// IsRunning می‌گوید پل فعال است یا نه.
func IsRunning() bool {
	bridgeMu.Lock()
	defer bridgeMu.Unlock()
	return bridgeStack != nil
}
