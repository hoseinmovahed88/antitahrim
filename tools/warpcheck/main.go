// warpcheck ثبت‌نام رایگان WARP را از بیرون برنامه اندروید امتحان می‌کند.
//
// همان درخواستی را می‌فرستد که برنامه اندروید می‌فرستد، تا اگر Cloudflare
// قالب پاسخ یا نسخه API را عوض کرد، سریع بفهمیم و کد اندروید را تطبیق دهیم.
//
//	go run ./tools/warpcheck
package main

import (
	"bytes"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"
)

const (
	apiHost    = "https://api.cloudflareclient.com"
	apiVersion = "v0a2158"
)

type regResponse struct {
	ID     string `json:"id"`
	Token  string `json:"token"`
	Config struct {
		ClientID string `json:"client_id"`
		Peers    []struct {
			PublicKey string `json:"public_key"`
			Endpoint  struct {
				V4   string `json:"v4"`
				V6   string `json:"v6"`
				Host string `json:"host"`
			} `json:"endpoint"`
		} `json:"peers"`
		Interface struct {
			Addresses struct {
				V4 string `json:"v4"`
				V6 string `json:"v6"`
			} `json:"addresses"`
		} `json:"interface"`
	} `json:"config"`
}

func main() {
	curve := ecdh.X25519()
	priv, err := curve.GenerateKey(rand.Reader)
	if err != nil {
		fail("تولید کلید ناموفق بود: %v", err)
	}
	pubB64 := base64.StdEncoding.EncodeToString(priv.PublicKey().Bytes())
	fmt.Println("کلید عمومی تولیدشده:", pubB64)

	body, _ := json.Marshal(map[string]string{
		"key":           pubB64,
		"install_id":    "",
		"fcm_token":     "",
		"tos":           time.Now().UTC().Format("2006-01-02T15:04:05.000Z"),
		"model":         "PC",
		"serial_number": "",
		"locale":        "en_US",
	})

	req, _ := http.NewRequest("POST", apiHost+"/"+apiVersion+"/reg", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json; charset=UTF-8")
	req.Header.Set("User-Agent", "okhttp/3.12.1")
	req.Header.Set("CF-Client-Version", "a-6.10-2158")
	req.Header.Set("Accept", "application/json")

	resp, err := (&http.Client{Timeout: 30 * time.Second}).Do(req)
	if err != nil {
		fail("درخواست ثبت‌نام نرسید: %v", err)
	}
	defer resp.Body.Close()

	raw, _ := io.ReadAll(resp.Body)
	fmt.Println("کد پاسخ:", resp.StatusCode)
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		fail("ثبت‌نام رد شد: %s", string(raw))
	}

	var parsed regResponse
	if err := json.Unmarshal(raw, &parsed); err != nil {
		fail("پاسخ قابل خواندن نبود: %v\n%s", err, string(raw))
	}

	if len(parsed.Config.Peers) == 0 {
		fail("پاسخ هیچ همتایی نداشت. قالب API عوض شده است.\n%s", string(raw))
	}
	peer := parsed.Config.Peers[0]

	if parsed.Config.Interface.Addresses.V4 == "" || peer.PublicKey == "" {
		fail("فیلدهای لازم در پاسخ نبودند. قالب API عوض شده است.\n%s", string(raw))
	}

	fmt.Println()
	fmt.Println("همه فیلدهایی که برنامه اندروید لازم دارد موجود است:")
	fmt.Println("  آدرس داخلی v4 :", parsed.Config.Interface.Addresses.V4)
	fmt.Println("  آدرس داخلی v6 :", trim(parsed.Config.Interface.Addresses.V6, 24))
	fmt.Println("  کلید همتا     :", peer.PublicKey)
	fmt.Println("  نقطه اتصال    :", peer.Endpoint.Host, peer.Endpoint.V4)
	fmt.Println("  شناسه دستگاه  :", trim(parsed.ID, 12))
	fmt.Println()

	const wellKnownPeer = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
	if peer.PublicKey != wellKnownPeer {
		fmt.Println("توجه: کلید همتا با مقدار پیش‌فرض داخل برنامه فرق دارد.")
		fmt.Println("برنامه کلید را از پاسخ می‌خواند، پس مشکلی نیست.")
	}

	fmt.Println("نتیجه: ثبت‌نام خودکار WARP کار می‌کند.")
}

func trim(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}

func fail(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "خطا: "+format+"\n", args...)
	os.Exit(1)
}
