# J2ME Slot Runner

Trình giả lập game/app J2ME (`.jar` / `.jad`) cho Android, chạy **tối đa 20 ô song song**, mỗi ô là
một tiến trình riêng, có **chạy ngầm khi tắt màn hình** và **proxy cho kết nối mạng của game**.

Đây là bản fork của [J2ME-Loader](https://github.com/nikita36078/J2ME-Loader) (Apache-2.0). Lõi
emulator (MIDP 2.0/CLDC 1.1, lcdui, game API, RMS, MMAPI, M3G, Mascot Capsule v3, các mở rộng
Nokia/Siemens/Samsung/Motorola/Vodafone/Sony Ericsson/Sprint, PIM, location, sensor, SMS, Bluetooth,
FileConnection, SSL) được giữ nguyên; chỉ thêm gói `com.example.j2meslots` và sửa tối thiểu phần lõi
(xem [docs/CHANGES_vs_J2ME-Loader.md](docs/CHANGES_vs_J2ME-Loader.md)). README gốc:
[README-J2ME-Loader.md](README-J2ME-Loader.md).

| | |
|---|---|
| applicationId | `com.example.j2meslots` (Java namespace giữ `ru.playsoftware.j2meloader`) |
| minSdk / targetSdk / compileSdk | 21 / 34 / 37 |
| ABI | `arm64-v8a`, `armeabi-v7a`, `x86` (đổi bằng `-PslotAbis=...`) |
| Ngôn ngữ UI | mặc định tiếng Việt (`values/`), tiếng Anh (`values-en/`) + các bản dịch gốc |

## Build

Yêu cầu: JDK 21, Android SDK (platform `android-37.0`, build-tools 37), NDK `29.0.14206865`, Git.

```bash
git submodule update --init --depth 1
```

```bash
echo "sdk.dir=/đường/dẫn/android-sdk" > local.properties
```

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/J2ME-Slot-Runner-1.0.0-debug.apk`.
Bản release (`./gradlew assembleRelease`) có `debuggable=false`, R8; ký bằng `keystore.properties`
(khoá `storeFile`, `storePassword`, `keyAlias`, `keyPassword`) nếu có.

Unit test JVM (proxy SOCKS5/HTTP/CONNECT với server giả lập cục bộ):

```bash
./gradlew :app:testDebugUnitTest
```

## Sinh 20 slot

Android bắt buộc khai báo tĩnh mỗi Activity/Service chạy ở process riêng, nên các lớp
`SlotActivity1..20`, `SlotKeepService1..20`, bảng tra `SlotClasses` và khối manifest được sinh bởi
script (Python 3, không cần thư viện ngoài). Kết quả đã được commit sẵn.

```bash
python3 tools/gen_slots.py
```

```bash
python3 tools/gen_slots.py --check
```

`--count N` sinh ít ô hơn (nhớ sửa `SLOT_COUNT` trong `app/build.gradle`).

## Kiến trúc

```
GridActivity (process chính, LAUNCHER)
 ├─ Slots: prefs "slots_prefs" (count, ids, dark, keep_alive, path_N, name_N, …)
 ├─ ▶  Slots.start(ctx, N): đảm bảo configs/<game>, gói midletName, hsoSlot, keepAlive,
 │      proxy, legacyCerts vào Intent → SlotActivityN (NEW_TASK|MULTIPLE_TASK)
 ├─ ■  Slots.stop(ctx, N): broadcast nội bộ <package>.slots.STOP_N (setPackage)
 └─ trạng thái: khoá file files/slots/slotN.lock (heartbeat của process slot)

process ":slotN"
 ├─ SlotActivityN extends SlotActivityBase extends MicroActivity (J2ME-Loader, không đổi)
 │    SlotRuntime.init(): số ô, dataSuffix "_slotN", keepAlive, ProxyHolder ← Intent extras
 │    giữ khoá heartbeat, ghi preview files/slots/slotN.jpg mỗi 3 s, nhận STOP_N → finish + kill
 └─ SlotKeepServiceN (foreground, specialUse): wake lock + Wi-Fi lock khi chạy ngầm
```

* **Save riêng từng ô**: `AppClassLoader.setDataDir` nối `SlotRuntime.dataSuffix()` → RMS ở
  `data/<game>_slotN`. Cache và dex tạm cũng tách theo ô để các ô không xoá file của nhau.
* **Chạy ngầm**: khi bật, slot không gọi `pauseApp()` lúc rời màn hình và `Canvas` không gửi
  `hideNotify()` khi surface bị huỷ; service foreground giữ CPU/Wi-Fi.
* **Không đọc prefs chéo process**: mọi dữ liệu cho slot đi qua Intent extras.

## Proxy

Cài đặt → Proxy (hoặc ⋮ → Proxy): chế độ **Tắt / Dùng chung toàn app / Theo từng ô**; mỗi cấu hình
gồm loại (HTTP, HTTPS-CONNECT, SOCKS5), host, port, user, mật khẩu (EncryptedSharedPreferences),
danh sách bỏ qua (host, `*.domain`, IP, CIDR), nút **Test kết nối** (độ trễ hoặc lỗi cụ thể),
nhập/xuất `scheme://user:pass@host:port`. Ô dùng proxy có nhãn 🌐 trên tile.

| Kết nối J2ME | Khi có proxy |
|---|---|
| `http://` | HTTP: gửi absolute-form + `Proxy-Authorization`; HTTPS-CONNECT/SOCKS5: tunnel |
| `https://` | tunnel (CONNECT hoặc SOCKS5) rồi TLS trên tunnel |
| `socket://` | SOCKS5 `CONNECT` (DNS phía proxy) hoặc `CONNECT host:port` (kiểm tra 2xx) |
| `ssl://` | như socket rồi TLS trên tunnel; cho phép TLS 1.0/1.1 |
| `datagram://`, Bluetooth | **không** đi qua proxy (ghi rõ trong UI) |

Chứng chỉ TLS luôn được kiểm tra theo kho CA hệ thống; chỉ khi bật **“Cho phép chứng chỉ cũ”** cho
từng ô (có cảnh báo) mới chấp nhận chứng chỉ hết hạn/tự ký. App chỉ dùng proxy do người dùng nhập,
không chèn proxy ẩn, không thu thập lưu lượng, không ghi mật khẩu vào log.

## Quyền riêng tư

Báo cáo lỗi (ACRA) **tắt mặc định**; bật trong Cài đặt emulator thì báo cáo chỉ lưu trên máy, không
có sender gửi ra ngoài. `allowBackup=false`.

## Giới hạn đã biết

* Mỗi ô là một process với heap riêng: >4 ô chạy cùng lúc app sẽ cảnh báo vì tốn RAM.
* FileConnection (`fs/`) dùng chung giữa các ô; chỉ RMS là tách riêng.
* Thay đổi proxy/chạy ngầm áp dụng khi ô được khởi động (lại).
* Vuốt bỏ task của ô hoặc của màn hình lưới khỏi Recents sẽ dừng các ô đang chạy ngầm.

## Kiểm thử

Xem [docs/TESTING.md](docs/TESTING.md) (unit test + kịch bản kiểm thử chấp nhận trên thiết bị).

## Giấy phép

Apache License 2.0 — xem [LICENSE](LICENSE) và [NOTICE](NOTICE). Ghi nguồn J2ME-Loader cũng có trong
màn hình Giới thiệu của app.
