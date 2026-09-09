package azadcore

import (
	"errors"
	"fmt"
	"strings"
	"sync"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"

	// همه پروتکل‌ها و انتقال‌ها را ثبت می‌کند. بدون این، کانفیگ خوانده می‌شود
	// ولی هنگام ساخت اتصال می‌گوید پروتکل ناشناخته است.
	_ "github.com/xtls/xray-core/main/distro/all"
)

var (
	xrayMu       sync.Mutex
	xrayInstance *core.Instance
)

// XrayVersion نسخه هسته را برمی‌گرداند. برای گزارش تشخیصی به کار می‌آید.
func XrayVersion() string {
	return core.Version()
}

// StartXray هسته Xray را با کانفیگ داده‌شده بالا می‌آورد.
//
// کانفیگ باید یک ورودی socks روی 127.0.0.1 داشته باشد تا پل tun بتواند
// ترافیک را به آن بسپارد.
//
// نکته درباره حلقه: سوکت‌های خروجی خود Xray نباید دوباره وارد تونل شوند.
// این را سمت اندروید حل می‌کنیم، با کنار گذاشتن خود برنامه از VPN
// (addDisallowedApplication)، که از پاس دادن یک callback از Go به جاوا
// ساده‌تر و کم‌خطاتر است.
func StartXray(configJSON string) error {
	xrayMu.Lock()
	defer xrayMu.Unlock()

	if xrayInstance != nil {
		return errors.New("هسته Xray از قبل در حال اجراست")
	}
	if strings.TrimSpace(configJSON) == "" {
		return errors.New("کانفیگ Xray خالی است")
	}

	config, err := serial.LoadJSONConfig(strings.NewReader(configJSON))
	if err != nil {
		return fmt.Errorf("کانفیگ Xray خوانده نشد: %w", err)
	}

	instance, err := core.New(config)
	if err != nil {
		return fmt.Errorf("هسته Xray ساخته نشد: %w", err)
	}
	if err := instance.Start(); err != nil {
		return fmt.Errorf("هسته Xray بالا نیامد: %w", err)
	}

	xrayInstance = instance
	return nil
}

// StopXray هسته را می‌بندد. فراخوانی چندباره‌اش بی‌ضرر است.
func StopXray() {
	xrayMu.Lock()
	defer xrayMu.Unlock()

	if xrayInstance == nil {
		return
	}
	_ = xrayInstance.Close()
	xrayInstance = nil
}

// XrayRunning می‌گوید هسته فعال است یا نه.
func XrayRunning() bool {
	xrayMu.Lock()
	defer xrayMu.Unlock()
	return xrayInstance != nil
}
