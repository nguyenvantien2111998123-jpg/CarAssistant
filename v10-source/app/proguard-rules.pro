# ---------------------------------------------------------------------------
# 1) Xposed entry — assets/xposed_init trỏ tới class này BẰNG TÊN CHUỖI
# ---------------------------------------------------------------------------
-keep class com.carassistant.v10.aabridge.AACastBridge { public *; }
-keepnames class com.carassistant.v10.aabridge.AACastBridge

# Hook classes (giữ cho log/stacktrace đọc được)
-keep class com.carassistant.v10.hook.** { *; }

# ---------------------------------------------------------------------------
# 2) Android Auto SDK cũ: giữ TÊN vì SDK tự load class theo tên
#    (SupportLibViewLoader dùng LayoutInflater.Factory với tên class trong XML,
#     host có thể gọi method bằng reflection)
# ---------------------------------------------------------------------------
-keep class com.google.android.apps.auto.sdk.** { *; }
-keep class com.google.android.gms.car.** { *; }
-keepnames class com.google.android.apps.auto.sdk.** { *; }
-keepnames class com.google.android.gms.car.** { *; }

# ---------------------------------------------------------------------------
# 3) Thành phần app (manifest nạp bằng tên)
# ---------------------------------------------------------------------------
-keep class com.carassistant.v10.MainActivity { *; }
-keep class com.carassistant.v10.AACastCarActivity { *; }
-keep class com.carassistant.v10.AACastCarService { *; }
-keep class com.carassistant.v10.DockStateProvider { *; }
-keep class com.carassistant.v10.RootShellSession { *; }

# ---------------------------------------------------------------------------
# 4) Thư viện ngoài không có trong APK (annotation của GMS / JSR-305)
# ---------------------------------------------------------------------------
-dontwarn com.google.android.gms.common.**
-dontwarn javax.annotation.**
-dontwarn com.google.android.apps.auto.sdk.**
-dontwarn com.google.android.gms.car.**

# Không obfuscate để tránh trùng/đổi tên class với thư viện SDK đóng gói kèm.
-dontobfuscate

# TẮT TỐI ƯU HOÁ (đặc biệt là CLASS MERGING).
# Lý do: thư viện SDK đóng kèm đã chứa các cặp class abstract/concrete chung tên gốc.
# Nếu R8 gộp tiếp (horizontal/vertical merging), nó có thể đổi đích lệnh `new`
# từ class concrete sang class abstract còn lại -> java.lang.InstantiationError
# lúc runtime (crash trong CarActivityHostImpl khi Android Auto host CarActivity).
-dontoptimize
