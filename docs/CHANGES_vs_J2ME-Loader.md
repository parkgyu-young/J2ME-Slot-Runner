# Thay đổi so với J2ME-Loader

Gốc: `nikita36078/J2ME-Loader` commit `ba0a322` (“ASM: Rename conflicting Thread overrides”).
Nguyên tắc: thêm code mới trong `com.example.j2meslots`, sửa lõi ít nhất có thể. Mọi chỗ sửa trong
lõi đều có chú thích `J2ME Slot Runner`.

## Build / cấu hình

| File | Thay đổi | Lý do |
|---|---|---|
| `build.gradle` | `TARGET_SDK = 34` | yêu cầu targetSdk 34 |
| `settings.gradle` | `rootProject.name`; `google()`/`mavenCentral()` lên trước `mavenLocal`/plugin portal | tên dự án; tránh lỗi 429 khi resolve AGP qua plugin portal |
| `app/build.gradle` | `applicationId com.example.j2meslots`, version 1.0.0, `app_name` = J2ME Slot Runner; bỏ product flavors (play/open/fdroid/dev/midlet) → một biến thể; `FULL_EMULATOR`/`SLOT_COUNT` trong `defaultConfig`; `abiFilters` arm64-v8a, armeabi-v7a, x86; release `debuggable false`, chỉ ký khi có `keystore.properties`; `useLegacyPackaging` (extractNativeLibs); thêm `androidx.security:security-crypto` | yêu cầu A.2, A.4, G |
| `app/src/midlet/**`, `app/src/play/**`, `app/proguard-midlet.pro` | xoá | thuộc flavor “port MIDlet thành APK” không dùng |
| `gradlew` | thêm quyền thực thi | `./gradlew` chạy được ngay |
| `app/src/main/AndroidManifest.xml` | `GridActivity` là LAUNCHER, `MainActivity` (thư viện) giữ intent-filter VIEW cài jar/jad; thêm `ProxySettingsActivity`; khối 20 slot sinh tự động; quyền mạng/FGS/wake lock/pin; `WRITE_EXTERNAL_STORAGE` max 28, BLUETOOTH/ADMIN max 30; bỏ `requestLegacyExternalStorage`; `extractNativeLibs=true` | B, H |

## Lõi emulator (sửa tối thiểu)

| File | Thay đổi |
|---|---|
| `javax/microedition/shell/AppClassLoader.java` | `setDataDir` nối `SlotRuntime.dataSuffix()` → RMS riêng từng ô |
| `javax/microedition/shell/MicroLoader.java` | thư mục dex tạm `dex_opt_slotN`; `fileconn.dir.private` theo ô |
| `javax/microedition/util/ContextHolder.java` | cache `cache_slotN` (mỗi lần chạy MIDlet lõi xoá cache) |
| `javax/microedition/lcdui/Canvas.java` | không gửi `hideNotify` khi surface bị huỷ nếu ô chạy ngầm (và không gửi `showNotify` lặp); thêm `copyLastFrame()` cho preview |
| `org/microemu/cldc/http/Connection.java` | `openUrl()`: dùng `ProxiedHttpURLConnection` khi ô có proxy; bật TLS cũ; chứng chỉ chỉ nới lỏng khi ô cho phép |
| `org/microemu/cldc/socket/SocketConnection.java` | `ProxySockets.connect()` (trực tiếp / SOCKS5 / CONNECT) |
| `org/microemu/cldc/ssl/Connection.java` | bỏ TrustManager “tin tất cả”; TLS dựng trên socket (có thể là tunnel) bằng `LegacyTls.wrap()` |
| `ru/.../EmulatorApplication.java` | ACRA chỉ bật khi người dùng opt-in (`pref_crash_reports`, mặc định tắt) |
| `ru/.../crashes/AppCenterSenderFactory.java` | `enabled()` = false: không gửi báo cáo ra ngoài |
| `ru/.../util/Constants.java` | hằng `PREF_CRASH_REPORTS` |
| `ru/.../config/Config.java` | thư mục mặc định (bộ nhớ legacy) `J2ME-Slot-Runner` thay vì `J2ME-Loader` |
| `ru/.../info/AboutDialogFragment.java` | thêm đoạn ghi nguồn fork / J2ME-Loader |
| `ru/.../util/AppUtils.java` | xoá game → xoá save `_slotN` và bỏ gán khỏi ô |
| `ru/.../appsdb/AppDatabase.java` | `enableMultiInstanceInvalidation()` để lưới và thư viện (hai instance Room) cùng thấy game mới cài/xoá |
| `ru/woesss/j2me/installer/AppInstaller.java` | cập nhật đổi thư mục → chuyển save `_slotN` và đường dẫn trong ô |

## Tài nguyên

* `values/strings.xml`: chuỗi gốc chuyển sang **tiếng Việt mặc định** (dùng bản dịch `values-vi`
  có sẵn + dịch 52 chuỗi còn thiếu); bản tiếng Anh gốc chuyển sang `values-en/strings.xml`;
  `values-vi/` bị xoá (trùng); `resources.properties`: `unqualifiedResLocale=vi`.
* `xml/preferences.xml`: thêm công tắc “Báo cáo lỗi (chỉ lưu trên máy)”.
* Mới: `strings_slots.xml` (vi/en), `colors_slots.xml` (sáng / `values-night` tối),
  `styles_slots.xml`, layout `activity_grid`, `item_slot_tile`, `item_slot_add`,
  `activity_proxy_settings`, `menu/grid_menu`, drawable `bg_slot_*`, `ic_slot_notification`.

## Mã mới (`app/src/main/java/com/example/j2meslots`)

| Lớp | Vai trò |
|---|---|
| `Slots` | helper tĩnh ở launcher: prefs `slots_prefs`, `activity(n)`, `keepService(n)`, `assign/clear/name/path`, `count/setCount`, `start/stop/forceStop`, `previewFile`, `isRunning` (khoá file), `ensureConfig` |
| `SlotRuntime` | trạng thái trong process slot: `active()`, `dataSuffix()`, `keepRunning()`, `allowLegacyCerts()`, khoá heartbeat |
| `slot/SlotActivityBase` | kế thừa `MicroActivity`: init runtime trước lõi, nhận STOP_N, preview, không pause khi chạy ngầm, khởi động keep service |
| `slot/SlotKeepService` | FGS `specialUse`, wake lock + Wi-Fi lock, thông báo có nút Dừng, `onTaskRemoved` |
| `slot/SlotActivity1..20`, `slot/SlotKeepService1..20`, `slot/SlotClasses` | **sinh bởi `tools/gen_slots.py`** |
| `ui/GridActivity` | màn hình lưới |
| `ui/ProxySettingsActivity` | màn hình proxy |
| `proxy/ProxyConfig` | cấu hình, URI import/export, bypass host/IP/CIDR (không tra DNS) |
| `proxy/ProxyStore` | lưu EncryptedSharedPreferences; fallback không lưu mật khẩu |
| `proxy/ProxyHolder` | cấu hình proxy tĩnh trong process slot, toast lỗi proxy |
| `proxy/ProxySockets` | SOCKS5 (RFC 1928/1929, ATYP domain), HTTP CONNECT, lỗi chi tiết |
| `proxy/ProxiedHttpURLConnection` | client HTTP/1.1 với API `HttpURLConnection` qua proxy |
| `proxy/LegacyTls` | TLS trên socket có sẵn, bật TLS 1.0–1.3, tin-tất-cả chỉ khi opt-in |
| `proxy/ProxyTester`, `ProxyException`, `IpUtils` | nút Test, lỗi bản địa hoá, parse IP/CIDR |

## Khác

* `tools/gen_slots.py` – script sinh slot.
* `app/src/test/...` – unit test JVM cho proxy (server SOCKS5/HTTP giả lập).
* `README.md` (mới), `README-J2ME-Loader.md` (README gốc), `NOTICE`, `docs/`.
