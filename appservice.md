# Hướng dẫn Vận hành Hệ thống PTalk & CTS Dashboard

Tài liệu này tổng hợp toàn bộ quy trình cấu hình, cài đặt cơ sở dữ liệu, quản lý dịch vụ xác thực (Auth Service), cấu hình ứng dụng di động (Android App), và CTS Dashboard vận hành thực tế trên máy chủ.

---

## 1. Tổng quan kiến trúc hệ thống

```
┌─────────────┐      HTTPS       ┌───────────────────┐      Docker      ┌──────────────┐
│  Android App │ ──────────────> │  Cloudflare Tunnel │ ──────────────> │  Auth Service │
│  (Phone/Tab) │                 │  auth.ctslab.net   │                 │  port 8005    │
└─────────────┘                 └───────────────────┘                 └──────┬───────┘
                                                                             │
┌─────────────┐      HTTPS       ┌───────────────────┐      Docker      ┌──────▼───────┐
│  CTS Web    │ ──────────────> │  Cloudflare Tunnel │ ──────────────> │  PostgreSQL   │
│  Dashboard  │                 │ dashboard.ctslab.net│                 │  ptalk_auth   │
└─────────────┘                 └───────────────────┘                 │  port 5434    │
                                                                             └──────────────┘
```

- **Auth Service**: FastAPI + JWT (HS256), chạy trong Docker container `ptalk-auth` trên cổng `8005`.
- **CTS Dashboard (Frontend Web)**: Next.js 16 (App Router) + Tailwind CSS v4, chạy trong Docker container `dashboard-frontend` trên cổng `4321`.
- **Database (PostgreSQL)**: PostgreSQL chạy trên Docker container `cts-dashboard-db` (cấu hình mạng LAN ánh xạ cổng `5434` của Host vào `5432` của container).
- **Tunnels (Cloudflare)**:
  - `https://auth.ctslab.net` ──> `http://localhost:8005` (Auth API)
  - `https://dashboard.ctslab.net` ──> `http://localhost:4321` (Dashboard Web)

---

## 2. Nhật ký những việc ĐÃ LÀM ĐƯỢC

### A. CTS Dashboard (Phần Web)
- **Tái thiết kế Premium UI/UX:** Cập nhật toàn bộ giao diện theo phong cách **Glassmorphism Dark Mode & Bento Grid**, các hạt neon chuyển động mượt mà và glow hiệu ứng siêu sang.
- **Sửa lỗi xác thực (Auth Session):** 
  - Khắc phục lỗi NextAuth v5 bị rỗng/Loading thông tin người dùng ở Header/Sidebar bằng cách ánh xạ đúng `name` và `email` từ JWT của FastAPI vào token & session Next.js.
  - Đấu nối thành công nút **Đăng xuất (Sign Out)** chuyển hướng an toàn về `/login`.
- **Trang Tổng quan (Dashboard Overview):**
  - Biểu đồ tương tác SVG hoạt động trơn tru hiển thị lưu lượng truy cập.
  - Radar hiển thị trạng thái kết nối phần cứng Robot (Online/Offline/Error).
  - Khung thông báo các sự cố khẩn cấp (Critical Alerts).
- **Trang Quản lý User:** Lọc nâng cao theo Tier, Loại User (Child/Owner), Switch Toggle thay đổi trạng thái hoạt động nhanh và Modal xem hồ sơ 360° chi tiết.
- **Trang Thiết bị Robot:** Bảng Serial Number, tính năng gán người dùng vào thiết bị và mô phỏng cập nhật Firmware OTA hiển thị phần trăm tiến trình mượt mà.
- **Các trang Sản phẩm con:**
  - *Robot PTalk*: Xem nhật ký hội thoại thực tế của bé/người dùng với robot từ database kèm phân tích sắc thái cảm xúc (Sentiment).
  - *Kid Mentor*: Đồ thị theo dõi bài học đã học của bé.
- **Trang Settings & Monitor:** Tích hợp Alert Rules, Telegram Bot Token và theo dõi độ trễ hệ thống (System Latency Check).

### B. Database (Phần SQL)
- Thiết lập kết nối cơ sở dữ liệu **PostgreSQL** trực tiếp thông qua IP nội bộ mạng LAN của máy chủ **`192.168.1.1:5434`** giúp container Docker kết nối ổn định mà không bị chặn bởi NAT loopback hay firewall IP Public.
- Triển khai thành công bảng **`conversation_logs`** để lưu trữ nhật ký hội thoại thực tế giữa người dùng và robot.
- **Tái cơ cấu toàn bộ Database lên Schema v2** — Nâng từ 4 bảng lên **19 bảng** theo UC Diagram và System Flowchart:
  - **RBAC:** `roles`, `permissions`, `role_permissions`, `user_roles` (6 vai trò + 18 quyền)
  - **Session:** `user_sessions`, `password_reset_tokens`
  - **Products:** `products` (PTalk/KidMentor), `user_products`
  - **Devices:** `devices` (owner + assigned_user), `mqtt_clients`
  - **Telemetry:** `device_heartbeats`, `connection_events`
  - **Content:** `questions`, `quotas`, `quota_violations`

---

## 2.1. Quy tắc Phân cấp Quyền hạn & Gói Người dùng (Authorization & Tiers Policy)

Hệ thống điều khiển quyền hạn truy cập Web Dashboard và sử dụng dịch vụ dựa trên:
1. **Admin (`is_superuser = true` hoặc `subscription_tier = 'admin'`):** Toàn quyền quản trị, đọc, ghi, xoá, chỉnh sửa tài khoản người dùng, cấu hình quota.
2. **Gói Thường / Demo User (`subscription_tier = 'basic'`):** Không có quyền truy cập Web Dashboard (chuyển hướng sang `/unauthorized`). Chỉ dùng thử tính năng hạn chế.
3. **Pro User (`'pro'`) & Ultra User (`'ultra'`):** Có toàn quyền truy cập sử dụng đầy đủ tính năng của Web Dashboard.
4. **Mua thiết bị vật lý:** Khi phụ huynh sử dụng app di động **P Assistant** để cấu hình và liên kết thiết bị Robot PTalk vật lý thành công (thông qua API đăng ký device), hệ thống **mặc định nâng cấp tài khoản của họ lên Ultra User (`subscription_tier = 'ultra'`)** ngay lập tức.

---

## 3. Cấu hình Cơ sở dữ liệu (PostgreSQL SQL)

### File Schema đầy đủ
Đường dẫn: `/home/namnx/Ptalk_project/sql/schema_v2.sql`

### Lệnh áp dụng Schema v2 (19 bảng)
```bash
# Chạy toàn bộ schema v2 (an toàn — chỉ tạo mới, không DROP bảng cũ)
docker exec -i cts-dashboard-db psql -U postgres -d ptalk_auth < /home/namnx/Ptalk_project/sql/schema_v2.sql

# Kiểm tra số bảng (kỳ vọng: 19)
docker exec cts-dashboard-db psql -U postgres -d ptalk_auth -c "
SELECT count(*) AS total_tables FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE';
"
```

### Cấu trúc bảng chat `conversation_logs` (giữ nguyên)
```sql
CREATE TABLE conversation_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender VARCHAR(16) NOT NULL, -- 'user' hoặc 'robot'
    message TEXT NOT NULL,
    sentiment VARCHAR(16) DEFAULT 'neutral', -- 'positive', 'negative', 'neutral'
    created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_conversation_logs_user ON conversation_logs(user_id);
```

### Lệnh chạy SQL trên máy chủ (Seed dữ liệu mẫu cho chat)
Chạy lệnh sau trên terminal của máy chủ để seed dữ liệu thoại mẫu cho tài khoản `namnx`:
```bash
docker exec -it cts-dashboard-db psql -U postgres -d ptalk_auth -c "
INSERT INTO conversation_logs (user_id, sender, message, sentiment)
SELECT id, 'user', 'Chào robot, hôm nay tớ được 10 điểm Toán đấy!', 'positive'
FROM users WHERE username = 'namnx' LIMIT 1;

INSERT INTO conversation_logs (user_id, sender, message, sentiment)
SELECT id, 'robot', 'Tuyệt vời quá! Chúc mừng bạn nhé! Bạn có muốn cùng tớ giải thêm một bài toán vui không?', 'positive'
FROM users WHERE username = 'namnx' LIMIT 1;
"
```

---

## 4. Cấu hình & Chạy CTS Dashboard (Next.js Web)

### A. Tệp cấu hình môi trường `.env`
Đường dẫn: `/home/namnx/Ptalk_project/Dashboard/dashboard/.env`
```env
# PTalk JWT Auth Service API
AUTH_API_URL=https://auth.ctslab.net

# Cấu hình NextAuth (Bảo mật phiên đăng nhập)
AUTH_SECRET=d3600f688e154f8b92b67fcf99e52e46
AUTH_URL=https://dashboard.ctslab.net
AUTH_TRUST_HOST=true

# Kết nối Database PostgreSQL trực tiếp qua Host LAN Port 5434 công khai
DB_HOST=192.168.1.1
DB_PORT=5434
DB_NAME=ptalk_auth
DB_USER=postgres
DB_PASSWORD=SecurePassword2024

# Các API bên thứ ba khác
NEXT_PUBLIC_API_URL=http://171.226.10.121:8000
```

### B. Tệp cấu hình chạy Docker `docker-compose.yml`
Đường dẫn: `/home/namnx/Ptalk_project/Dashboard/dashboard/docker-compose.yml`
```yaml
version: '3.8'

services:
  dashboard-frontend:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: dashboard-frontend
    ports:
      - "4321:3000" # Map ra cổng 4321 để Cloudflare Tunnel chuyển tiếp
    restart: unless-stopped
    env_file:
      - .env
```

### C. Lệnh khởi chạy Web Dashboard
Bạn di chuyển vào thư mục dự án và chạy Docker Compose để build & khởi chạy ngầm ứng dụng:
```bash
cd /home/namnx/Ptalk_project/Dashboard/dashboard
docker compose up -d --build
```
*Để dừng dịch vụ:* `docker compose down`  
*Để xem nhật ký:* `docker logs dashboard-frontend --tail 50 -f`

---

## 5. Những việc CẦN LÀM TIẾP THEO (To-do / Next Steps)

1. **Đồng bộ hóa User thực tế trên Mobile App:**
   - Liên kết OIDC đăng nhập SSO cho app di động PTalk Assistant (Android) để khi user đăng nhập trên app di động sẽ đồng nhất với tài khoản trên Dashboard.
2. **Triển khai Endpoint Đếm Quota Lượt dùng cho Web:**
   - Hoàn thiện đếm request logs tự động của các sản phẩm (Kid Mentor) thông qua middleware ghi nhận log vào DB thay vì giả lập.
3. **Đấu nối MQTT Broker thật cho Giám sát thiết bị:**
   - Kết nối Radar giám sát robot thời gian thực trên Overview với MQTT Broker thực tế để biết chính xác robot nào Online/Offline thay vì mock đếm.
