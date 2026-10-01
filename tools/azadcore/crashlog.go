package azadcore

import (
	"os"
	"sync"
	"syscall"
)

var (
	crashMu   sync.Mutex
	crashFile *os.File
)

// SetCrashLog خروجی خطای استاندارد فرایند را به یک فایل می‌برد.
//
// وقتی یک goroutine در Go وحشت می‌کند (panic) و کسی آن را نمی‌گیرد، زمان
// اجرای Go ردش را روی توصیف‌گر ۲ می‌نویسد و کل فرایند را می‌کشد. روی
// اندروید توصیف‌گر ۲ به هیچ‌جا وصل نیست، پس برنامه بی‌صدا ناپدید می‌شود و
// هیچ سرنخی نمی‌ماند؛ نه در گزارش جاوا و نه جای دیگری. recover هم کمکی
// نمی‌کند، چون فقط وحشت همان goroutine‌ای را می‌گیرد که در آن صدا زده شده و
// هسته Xray برای هر اتصال goroutine جدا می‌سازد.
//
// با بردن توصیف‌گر ۲ به یک فایل، رد کامل وحشت روی دیسک می‌ماند و دفعه بعد
// که برنامه باز شود در گزارش نشان داده می‌شود. فقط یک بار در عمر فرایند اثر
// دارد؛ فراخوانی‌های بعدی کاری نمی‌کنند.
func SetCrashLog(path string) error {
	crashMu.Lock()
	defer crashMu.Unlock()

	if crashFile != nil {
		return nil
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if err := syscall.Dup3(int(f.Fd()), 2, 0); err != nil {
		f.Close()
		return err
	}
	// فایل باز می‌ماند؛ بستنش توصیف‌گر ۲ را هم بی‌اثر می‌کرد
	crashFile = f
	return nil
}
