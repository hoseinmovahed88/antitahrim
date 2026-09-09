//go:build tools

// این فایل هیچ‌وقت کامپایل نمی‌شود.
//
// تنها کارش این است که وابستگی به golang.org/x/mobile را در go.mod نگه دارد.
// gomobile bind اصرار دارد که این وابستگی در گراف ماژول باشد، ولی چون کد ما
// هیچ‌جا از آن import نمی‌کند، go mod tidy بی‌رحمانه حذفش می‌کند.
// این import با برچسب ساخت، مسئله را حل می‌کند.
package azadcore

import _ "golang.org/x/mobile/bind"
