# Pickleball Video — Android Camera Livestream App

App Android cố định ở sân, tự động stream camera + scoreboard overlay lên YouTube Live.

## Tính năng
- Camera preview + RTMP stream lên YouTube
- Scoreboard overlay realtime từ Firebase (đọc score từ trọng tài)
- Tự động hiện/ẩn scoreboard theo trạng thái trận đấu
- Hiện quảng cáo khi trọng tài tạm dừng
- Chạy liên tục cả ngày giải, không cần thao tác

## Setup
1. Mở app → đăng nhập (tài khoản trọng tài)
2. Chọn giải → chọn sân
3. App tự lấy RTMP URL từ backend → bắt đầu stream
4. Mọi thứ tự động theo trọng tài

## Tech Stack
- Kotlin + Jetpack Compose
- CameraX (camera)
- Canvas overlay (scoreboard trên camera frame)
- rtmp-rtsp-stream-client-java (RTMP push)
- Firebase Realtime Database (listen score realtime)
- Retrofit (API calls)

## Build
```bash
./gradlew assembleDebug
```

## Yêu cầu
- Android 8.0+ (API 26+)
- Camera permission
- Internet permission
- WiFi ổn định tại sân
- Cắm sạc liên tục
