// Package tun2socks پلی است بین دستگاه tun اندروید و یک پروکسی SOCKS محلی.
//
// چرا لازم است: Psiphon یک پروکسی SOCKS روی گوشی باز می‌کند، ولی VpnService
// اندروید بسته‌های خام IP می‌دهد. این بسته‌ها باید به اتصال‌های TCP و UDP
// ترجمه و به آن پروکسی سپرده شوند. این کار یک پشته شبکه در فضای کاربر
// می‌خواهد که اینجا از gVisor می‌آید.
//
// این بسته با gomobile به یک کتابخانه اندروید تبدیل می‌شود:
//
//	gomobile bind -target=android/arm64,android/arm -o tun2socks.aar .
package tun2socks

import (
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/engine"
)

var (
	mu      sync.Mutex
	running bool
)

// Start ترجمه بسته‌ها را روی توصیف‌گر فایل دستگاه tun آغاز می‌کند.
//
// fd همان چیزی است که VpnService.establish برمی‌گرداند. مالکیتش دست
// فراخواننده می‌ماند؛ اینجا فقط از آن خوانده و روی آن نوشته می‌شود.
//
// socksPort پورت پروکسی SOCKS محلی است که Psiphon باز کرده.
func Start(fd int, socksPort int, mtu int) error {
	mu.Lock()
	defer mu.Unlock()

	if running {
		return errors.New("tun2socks از قبل در حال اجراست")
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

	engine.Insert(&engine.Key{
		Device:     fmt.Sprintf("fd://%d", fd),
		Proxy:      fmt.Sprintf("socks5://127.0.0.1:%d", socksPort),
		MTU:        mtu,
		LogLevel:   "warning",
		UDPTimeout: 60 * time.Second,
	})
	engine.Start()

	running = true
	return nil
}

// Stop ترجمه را متوقف می‌کند. فراخوانی چندباره‌اش بی‌ضرر است.
func Stop() {
	mu.Lock()
	defer mu.Unlock()

	if !running {
		return
	}
	engine.Stop()
	running = false
}

// IsRunning می‌گوید پل فعال است یا نه.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}
