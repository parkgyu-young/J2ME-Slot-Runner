# Kiểm thử J2ME Slot Runner

## 1. Tự động (không cần thiết bị)

```bash
./gradlew :app:testDebugUnitTest
```

25 test JVM (`app/src/test/java/com/example/j2meslots/proxy/`):

* `ProxyConfigTest` – parse/xuất `scheme://user:pass@host:port` (IPv6, ký tự đặc biệt, cổng mặc định,
  URI sai), `toString()` không lộ mật khẩu, bypass host/`*.domain`/IP/CIDR v4-v6, loopback luôn
  đi thẳng, CIDR không tra DNS, Base64.
* `ProxyConnectionTest` – server giả lập trong test: SOCKS5 có/không xác thực, **DNS phía proxy**
  (host `game.invalid` không phân giải được ở máy), sai mật khẩu → `AUTH_FAILED`, thiếu mật khẩu →
  `AUTH_REQUIRED`, proxy HTTP forward (absolute-form + `Proxy-Authorization`), 407 → `AUTH_FAILED`,
  tunnel CONNECT cho http, POST có body, 404 + error stream, proxy không mở cổng → `PROXY_REFUSED`,
  socket thô qua SOCKS5 và CONNECT, và `HttpConnection` J2ME (lớp GCF thật) đi qua proxy của ô.

```bash
python3 tools/gen_slots.py --check
```

Báo lỗi nếu các lớp/khối manifest sinh tự động bị sửa tay hoặc lệch số ô.

## 2. MIDlet kiểm thử

`tools/testmidlet/prebuilt/NetRmsTest.jar` + `.jad` (nguồn trong `tools/testmidlet/src`, build lại
bằng `tools/testmidlet/build.sh`). Màn hình gồm:

* **RMS launches** – tăng 1 mỗi lần MIDlet khởi động (lưu RMS) → kiểm tra save riêng từng ô.
* **Ticks** – đếm mỗi giây kèm thời gian thực → kiểm tra chạy ngầm (ticks ≈ số giây thật).
* Menu **HTTP test** (`Test-URL`, mặc định `http://example.com/`), **Socket test**
  (`Test-Socket`, mặc định `example.com:80`), **Reset RMS**.

Sửa `Test-URL`/`Test-Socket` trong file `.jad` để trỏ tới máy chủ của bạn.

## 3. Proxy dùng để kiểm thử

Một proxy có log và xác thực, ví dụ [pproxy](https://pypi.org/project/pproxy/) trên máy tính cùng mạng:

```bash
pip install pproxy
```

```bash
pproxy -l "socks5://:1080#alice:secret" -l "http://:8080#alice:secret" -vv
```

Mỗi kết nối được in ra (`socks5 ... -> example.com:80`, `http ... -> example.com:80`).

## 4. Kịch bản chấp nhận (thiết bị Android 8, 11, 14)

Cài APK debug:

```bash
adb install -r app/build/outputs/apk/debug/J2ME-Slot-Runner-1.0.0-debug.apk
```

Theo dõi tiến trình các ô:

```bash
adb shell "ps -A | grep j2meslots"
```

| # | Bước | Kỳ vọng |
|---|---|---|
| 1 | 📁 → cài 3 file `.jar` khác nhau (chọn nhiều file một lúc) và `NetRmsTest.jad` (đặt cạnh `.jar`) | hộp thoại “Đang cài i/n…”, “Đã cài: 3”; JAD không khớp JAR thì có cảnh báo và cho bỏ qua; cài lại hỏi cài lại/cập nhật |
| 2 | “＋ Thêm ô” tới 5 ô; gán 3 game cho ô 1–3; ▶ lần lượt; dùng Recents chuyển qua lại | mỗi ô là một task + process `com.example.j2meslots:slotN`; tile “● Đang chạy”, preview cập nhật; không crash |
| 3 | Gán `NetRmsTest` cho ô 4 và ô 5; chạy/dừng mỗi ô vài lần | “RMS launches” của ô 4 và ô 5 tăng độc lập (thư mục `data/<game>_slot4`, `_slot5`) |
| 4 | ⚙ → bật “Chạy ngầm…” (cho phép thông báo, tắt tối ưu pin); chạy `NetRmsTest`; tắt màn hình 10 phút | có thông báo “Ô #N đang chạy ngầm”; bật màn hình: Ticks ≈ 600 so với lúc tắt |
| 5 | ■ một ô đang chạy | chỉ process `:slotN` đó biến mất; các ô khác vẫn chạy; tile về “○ Sẵn sàng” |
| 6a | Proxy → “Dùng chung toàn app”, SOCKS5 `<ip>:1080`, alice/secret → **Test kết nối**; chạy `NetRmsTest` → HTTP test, Socket test | Test hiện “✓ Thành công: … ms”; log pproxy có kết nối tới `example.com:80`; tile có 🌐 |
| 6b | Như 6a với HTTP `<ip>:8080` | log pproxy phía http |
| 6c | Đổi mật khẩu thành sai → Test; chạy lại ô → HTTP test | “✗ Lỗi: Proxy … từ chối đăng nhập: sai user hoặc mật khẩu”; trong game có toast cùng nội dung |
| 7 | Lặp 1–6 trên Android 8, 11, 14 | trên 14 dịch vụ foreground `specialUse` khởi động không lỗi |

Thêm: kill một ô bằng `adb shell am kill` hoặc để hệ thống thu hồi RAM → tile tự về “Sẵn sàng”
trong ~1 s; vuốt bỏ task của ô khỏi Recents → ô dừng và preview bị xoá.

## 5. Ghi chú

* Môi trường phát triển của bản bàn giao không có thiết bị/KVM nên mục 4 chưa được chạy tại đây;
  đã kiểm chứng: build debug/release, manifest đã merge (20 process, FGS `specialUse` + property,
  quyền, `extractNativeLibs`), 3 ABI native, unit test proxy, và `NetRmsTest.jar` chuyển DEX được
  bằng chính bộ chuyển của emulator.
* Bộ chuyển JAR→DEX của J2ME-Loader (ASM `COMPUTE_MAXS` + dx) tính thiếu operand stack với một số
  vòng lặp có `try/catch` và phép tính `long` khi biên dịch bằng javac hiện đại (`SimException:
  stack: overflow`). Đây là hành vi sẵn có của upstream (không bị fork này thay đổi); MIDlet kiểm
  thử đã được viết để tránh mẫu này.
