# Kiến trúc Android Box cho CarAsistant

## Mục tiêu

Tách logic điều phối khỏi Android Auto host và khỏi Activity UI. Android Auto chính thức
vẫn là transport/car host chính; CarAsistant không tự ý gọi Activity trên màn hình xe
ngay khi chỉ nhận được tín hiệu kết nối.

## Các ranh giới

- **Core**: lệnh, cấu hình, trạng thái phiên, điều phối tác vụ. Không phụ thuộc LSPosed,
  Shizuku hay class nội bộ của Gearhead.
- **Connection adapter**: phát hiện Android Auto connected/disconnected. Có thể có
  adapter công khai và adapter đặc quyền tùy thiết bị; không được tự khởi chạy UI.
- **Android Auto adapter**: AACastCarService + V10CarActivity; chỉ host Android Auto
  mới mở car session chính thức.
- **Display backend**: hợp đồng CarDisplayBackend; backend V10 hiện tại được giữ làm
  implementation đang chạy. Backend Shizuku/VirtualDisplay chỉ được thêm sau khi có
  spike độc lập chứng minh surface, input, lifecycle và tương thích Android 12.
- **Privileged adapter**: Root/LSPosed (hoặc thử nghiệm Zygisk) là lớp tùy chọn cho những
  thao tác Android không có API công khai; Core không gọi hook trực tiếp.

## Trạng thái phiên

CarConnectionState và CarActivity session là hai tín hiệu riêng. CONNECTED không
đồng nghĩa với CarAsistant đang foreground trên màn hình xe. CarSessionCoordinator
chỉ attach display backend sau callback onCarSessionStarted() từ adapter car UI.
DISCONNECTED kết thúc session và detach surface.

## Quy tắc bảo toàn

- Giữ AACastCarService/V10CarActivity làm backend mặc định.
- Không thay đổi MediaSessionBridge, MediaAutoRestoreService, SilentAudioTrackFallback,
  SteeringWheelMediaKeyHandler hoặc các hook phím vô lăng trong giai đoạn tách Core.
- Không thêm Shizuku dependency vào APK chính cho tới khi backend thử nghiệm được chứng minh.
- Không đưa MapLibre trở lại; Navigation tiếp tục mở app bản đồ ngoài theo target đã chọn.

## Tiến độ

1. Voice command execution đã được tách khỏi V10CarActivity.
2. Bổ sung hợp đồng Core cho connection monitor và display backend, cùng state coordinator
   độc lập và unit tests.
3. Gắn adapter V10 vào coordinator sau khi kiểm chứng lifecycle đầy đủ.
4. Shizuku VirtualDisplay là backend thử nghiệm riêng; không thay thế backend đang dùng
   cho tới khi có kết quả build và test trên POCO F1 Android 12 + Android Auto thực.
