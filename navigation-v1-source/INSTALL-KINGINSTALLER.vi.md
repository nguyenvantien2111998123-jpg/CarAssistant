# Cài AACast Doudou bằng KingInstaller

[English](README.md) | [Tiếng Việt](README.vi.md) | **Hướng dẫn cài đặt**

Android Auto chỉ hiển thị những app có trường **"Installed by" = Google Play Store**
(`com.android.vending`). Cài bằng `adb install` sẽ bị gắn là `com.android.shell` và Android Auto
bỏ qua. **KingInstaller** cài APK với danh nghĩa Play Store, nhờ đó AACast Doudou mới xuất hiện
trên màn hình xe.

- KingInstaller: <https://github.com/fcaronte/KingInstaller/releases> (bản mới nhất: **v2.1**)
- Yêu cầu của AACast Doudou: xem [mục 2 — Yêu cầu](README.vi.md#2-yêu-cầu) (root + LSPosed là bắt buộc).

---

## 1. Chuẩn bị

| Mục | Bắt buộc | Ghi chú |
|---|---|---|
| Android | 10+ (API 29+) | |
| Root | Có | Magisk / KernelSU / APatch — KingInstaller dùng `su` để ghi đè "Installed by"; AACast Doudou cũng cần `su` để chạy app trong từng ô |
| LSPosed | Có | Cài sẵn, sẽ bật ở bước 4 |
| Android Auto | Có | `com.google.android.projection.gearhead` |
| CH Play (GMS) | Có | Máy không có Google Play sẽ không dùng được cách này |
| APK AACast Doudou | Có | Bản release do tác giả chia sẻ (nhóm Facebook) hoặc tự build: `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk` |

Tải KingInstaller:

1. Mở <https://github.com/fcaronte/KingInstaller/releases/latest>.
2. Tải file **`KingInstaller-v2.1.apk`** (hoặc bản mới hơn).

---

## 2. Cài KingInstaller

1. Mở file `KingInstaller-v2.1.apk` vừa tải.
2. Cho phép **"Cài đặt ứng dụng không rõ nguồn gốc"** cho trình duyệt / trình quản lý file → **Cài đặt**.
3. Mở **KingInstaller**.

---

## 3. Cài AACast Doudou bằng KingInstaller

Mở KingInstaller, chọn file APK AACast Doudou bằng một trong các cách: bấm nút chọn file trong app,
**Mở bằng → KingInstaller** từ trình quản lý file, hoặc **Chia sẻ → KingInstaller** (từ v2.0).

Thử lần lượt 3 cách dưới đây, dừng lại ngay khi **Diagnostic Checker** báo đúng:

### Cách 1 — Classic (thử trước)

- Chọn APK → bấm **Install**, **không** bật switch nào.
- Phần lớn ROM stock Android 10–16 tự gắn đúng nguồn cài.

### Cách 2 — Shizuku Trick

- Dùng khi Cách 1 cài được nhưng Android Auto không thấy app.
- Cài và chạy [Shizuku](https://shizuku.rikka.app/), bật switch **Shizuku Trick** trong KingInstaller rồi cài lại.
- **Xiaomi / POCO / Redmi (MIUI/HyperOS)**: shizuku thường bị chặn — chuyển thẳng sang Cách 3.

### Cách 3 — Root Trick

- Dùng khi hai cách trên thất bại (bắt buộc với Xiaomi / POCO / Redmi).
- Bật switch **Root Trick** → KingInstaller xin quyền `su` → cấp **vĩnh viễn** trong trình quản lý root → cài lại.

### Xác nhận đã cài đúng

1. Trong KingInstaller, mở **Diagnostic Checker** → chọn **AACast Doudou**.
2. Kết quả cần đạt: **Installed by = Google Play Store** (`com.android.vending`).
   - Nếu vẫn là `com.android.shell` / `com.android.packageinstaller` → đổi sang cách khác và cài lại.

> **Lỗi chữ ký khi cài đè**: nếu trước đó đã `adb install` bản khác keystore, hãy gỡ bản cũ rồi cài lại
> (sẽ mất cấu hình bố cục). Từ đó về sau, cập nhật app qua chính KingInstaller để giữ đúng nguồn cài.
>
> Sau khi cài lại, kiểm tra lại **LSPosed** đã bật module và **quyền root vĩnh viễn** đã cấp cho AACast Doudou chưa.

---

## 4. Đưa app lên Android Auto

1. **Bật nguồn không xác định cho Android Auto**: mở Android Auto → **Cài đặt** → bấm liên tục vào
   **Phiên bản (Version)** để mở Developer mode → **Developer settings** → bật **Unknown sources**.
   (KingInstaller có nút mở nhanh trang cài đặt Android Auto.)
2. **LSPosed** → Modules → **AACast Doudou** → bật → chọn scope:
   - `android` (System Framework)
   - `Android Auto` (`com.google.android.projection.gearhead`)
3. Khởi động lại `system_server` (LSPosed sẽ hỏi) hoặc reboot máy.
4. `adb shell am force-stop com.google.android.projection.gearhead` để hook được cài lại.
5. Cấp quyền **root vĩnh viễn** cho AACast Doudou trong trình quản lý root.
6. Mở app trên điện thoại → chọn bố cục và gán app cho từng ô.
7. Kết nối điện thoại với xe (USB hoặc không dây) → chọn **AACast Doudou** trên màn hình xe.
8. Trên màn hình xe: bấm **Start split screen**, dùng nút **⋮** để chọn app, kéo **⋮** sang ô khác
   để đổi chỗ, kéo thanh giữa để chỉnh kích thước.

---

## 5. Xử lý sự cố

| Triệu chứng | Xử lý |
|---|---|
| Không cài được, báo "App not installed" | Gỡ bản AACast cũ (khác chữ ký) rồi cài lại; thử lần lượt Classic → Shizuku → Root |
| Đã cài nhưng Android Auto không thấy app | Kiểm tra "Installed by" = Google Play Store bằng Diagnostic Checker; bật **Unknown sources** trong AA Developer settings; force-stop Android Auto hoặc reboot |
| Xiaomi / POCO / Redmi cài hoài không được | Bật **Root Trick** (Classic và Shizuku bị MIUI/HyperOS chặn) |
| Android Auto thấy app nhưng bấm vào báo lỗi khi chạy | Xem [mục 6 — Xử lý sự cố](README.vi.md#6-xử-lý-sự-cố) trong README |
| App ngừng hoạt động sau khi Android Auto cập nhật | Hook fail-open nên app vẫn mở; xem log bên dưới và cập nhật hằng số class trong `hook/` |

Log hữu ích:

```bash
adb logcat -v time -s AACastDoudou AACastDock "AACast Display" "AACast Input"
adb shell dumpsys display | grep -A3 "AACast Doudou"
```

---

## Liên kết

- KingInstaller — <https://github.com/fcaronte/KingInstaller> (GPL-3.0)
- AACast Doudou — [README.vi.md](README.vi.md) · [Nhóm Facebook](https://www.facebook.com/groups/2327824764621369)
