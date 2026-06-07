# Tài liệu Yêu cầu Kỹ thuật Hệ thống Backend (BE) & Máy chủ (Server)
## Dự án: Hệ sinh thái Robot Assistant PTalk & CTS Dashboard

Tài liệu này đặc tả toàn bộ các yêu cầu cấu hình máy chủ, cơ sở dữ liệu, mạng kết nối và hợp đồng API dành cho đội ngũ phát triển Backend (FastAPI) và Quản trị hệ thống (SysAdmin/DevOps). 

---

## 1. Yêu cầu Kiến trúc & Phân bổ Cổng Dịch vụ (Ports)

Hệ thống được vận hành hoàn chỉnh dưới dạng các container Docker trên máy chủ. Yêu cầu phân bổ cổng và ánh xạ đường dẫn như sau:

| Tên Dịch vụ | Công nghệ | Cổng nội bộ (Container) | Cổng ánh xạ (Host Server) | Địa chỉ Domain Public |
| :--- | :--- | :--- | :--- | :--- |
| **Auth API Service** | FastAPI (Python) | `8005` | `8005` | `https://auth.ctslab.net` |
| **Database Server** | PostgreSQL | `5432` | `5434` | Chỉ truy cập nội bộ |
| **CTS Web Dashboard**| Next.js 16 (React) | `3000` | `4321` | `https://dashboard.ctslab.net` |

### ⚠️ Quan trọng: Cấu hình Mạng kết nối nội bộ (LAN Loopback)
- **Vấn đề:** Máy chủ được bảo vệ bởi tường lửa và các quy tắc NAT/Routing nghiêm ngặt, ngăn chặn các container Docker gọi trực tiếp đến IP Public (`171.226.10.121:5434`).
- **Giải pháp cho BE/Server:** Trực tiếp cấu hình và cho phép các container kết nối đến Database qua địa chỉ IP mạng LAN nội bộ của máy chủ là **`192.168.1.1:5434`**. 
- Hệ thống DNS hoặc file `.env` của các service phải sử dụng kết nối LAN này để đảm bảo độ trễ thấp và kết nối ổn định không bị timeout.

---

## 2. Đặc tả Cơ sở dữ liệu (PostgreSQL Schema v2)

Database `ptalk_auth` gồm **19 bảng**, thiết kế theo UC Diagram và System Flowchart hệ sinh thái PTalk.

> **File SQL đầy đủ:** `/home/namnx/Ptalk_project/sql/schema_v2.sql`

### Sơ đồ tổng quan 19 bảng

| # | Bảng | Nhóm | Mô tả | UC liên quan |
|:--|:---|:---|:---|:---|
| 1 | `users` | Identity | Định danh tập trung (Unified Identity) | Login, Register |
| 2 | `roles` | RBAC | Vai trò: admin, member, user, product_admin, support, viewer | Manage Role |
| 3 | `permissions` | RBAC | Quyền hạn: device:read, user:write, mqtt:admin... (18 quyền) | Manage Permission |
| 4 | `role_permissions` | RBAC | Gán permission vào role (N:N) | Manage Role |
| 5 | `user_roles` | RBAC | Gán role cho user (N:N) | Manage User |
| 6 | `user_sessions` | Auth | Phiên đăng nhập (revokable, device info) | Manage/Revoke Session |
| 7 | `refresh_tokens` | Auth | JWT refresh tokens (legacy, giữ nguyên) | Reset Token |
| 8 | `password_reset_tokens` | Auth | Token đặt lại mật khẩu | Forgot Password |
| 9 | `products` | Product | Sản phẩm: PTalk, Kid Mentor | Unified Identity |
| 10 | `user_products` | Product | User enrollment vào product (N:N) | Manage User |
| 11 | `devices` | IoT | Thiết bị phần cứng (owner + assigned_user) | Manage Device |
| 12 | `mqtt_clients` | IoT | MQTT client đăng ký per device | Register/Manage MQTT |
| 13 | `device_heartbeats` | Telemetry | Heartbeat: RSSI, CPU, RAM, uptime | Send Heartbeat |
| 14 | `connection_events` | System Log | Log kết nối/ngắt/OTA | Log Connection Event |
| 15 | `conversation_logs` | Chat | Lịch sử chat user ↔ robot | View/Manage Question |
| 16 | `questions` | Content | Câu hỏi + câu trả lời từ AI/Robot | View/Manage Question |
| 17 | `quotas` | Quota | Hạn mức sử dụng per user per resource | Manage Quota |
| 18 | `quota_violations` | Quota | Log vi phạm quota | Log Quota Violation |
| 19 | `request_logs` | Legacy | Đếm request/ngày (legacy, giữ nguyên) | View Statistic |

---

### 2.1. Quy tắc Phân cấp Quyền hạn & Gói Người dùng (Authorization & Tiers Policy)

Hệ thống phân cấp quyền truy cập Web Dashboard và các tính năng API dựa trên phân cấp Tiers sau:

1. **Quản trị viên (Admin - `is_superuser = true` hoặc `subscription_tier = 'admin'`):**
   * **Quyền hạn:** Toàn quyền (Superuser). Có quyền đọc, thêm, sửa, xoá toàn bộ dữ liệu, quản lý tài khoản người dùng, cấu hình quota và xem toàn bộ log hệ thống.
2. **Gói Thường / Demo User (`subscription_tier = 'basic'`):**
   * **Quyền hạn:** **Không có quyền truy cập sử dụng Web Dashboard**. Nếu cố gắng đăng nhập vào Web, hệ thống sẽ tự động chuyển hướng sang trang `/unauthorized`. Chỉ dùng để demo tính năng cơ bản, hạn chế tối đa số lượng request API.
3. **Gói Pro User (`subscription_tier = 'pro'`) & Ultra User (`subscription_tier = 'ultra'`):**
   * **Quyền hạn:** Có toàn quyền truy cập sử dụng Web Dashboard để theo dõi Robot PTalk (lịch sử chat, cảm xúc) và xem tiến độ học tập trên Kid Mentor.
4. **Chính sách Thiết bị vật lý (Physical Device Owners):**
   * **Quy tắc đặc biệt:** Đối với bất kỳ tài khoản người dùng nào mua thiết bị vật lý và tiến hành kích hoạt/cấu hình thiết bị lần đầu tiên thành công (qua app di động P Assistant gửi request lên `/api/v1/devices`), hệ thống **bắt buộc phải tự động nâng cấp gói tài khoản của họ lên gói Ultra User (`subscription_tier = 'ultra'`)** mà không cần qua thanh toán thủ công.

---

### A. Bảng Người dùng (`users`)
Kho định danh tập trung (Unified Identity) cho toàn bộ hệ sinh thái (Web, Android App, Robot).

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(150) UNIQUE NOT NULL,
    email VARCHAR(254) UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    display_name VARCHAR(128),
    user_type VARCHAR(32) DEFAULT 'owner',     -- 'owner', 'child'
    subscription_tier VARCHAR(16) DEFAULT 'basic', -- 'basic', 'pro', 'ultra'
    phone_number VARCHAR(30),
    is_active BOOLEAN DEFAULT true,
    is_superuser BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    last_login_at TIMESTAMPTZ,
    tier_expires_at TIMESTAMPTZ
);
```

### B. Bảng RBAC (`roles`, `permissions`, `role_permissions`, `user_roles`)

```sql
-- Roles (6 vai trò mặc định đã seed)
CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(50) UNIQUE NOT NULL,    -- 'admin','member','user','product_admin','support','viewer'
    description VARCHAR(255),
    is_system BOOLEAN DEFAULT false,     -- true = không được xoá
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Permissions (18 quyền mặc định theo 8 module)
CREATE TABLE permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(100) UNIQUE NOT NULL,   -- 'device:read', 'user:write', 'mqtt:admin'
    description VARCHAR(255),
    module VARCHAR(50)                    -- 'device','user','mqtt','question','quota','session','stats','role','chat'
);

-- Role ↔ Permission (N:N)
CREATE TABLE role_permissions (
    role_id UUID REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- User ↔ Role (N:N)
CREATE TABLE user_roles (
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID REFERENCES roles(id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);
```

### C. Bảng Phiên đăng nhập (`user_sessions`, `password_reset_tokens`)

```sql
CREATE TABLE user_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(128) UNIQUE NOT NULL,
    ip_address VARCHAR(45),
    user_agent VARCHAR(512),
    device_info VARCHAR(255),        -- "Android 14 / PAssistant v1.0"
    is_revoked BOOLEAN DEFAULT false,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(128) UNIQUE NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT now()
);
```

### D. Bảng Thiết bị (`devices`, `mqtt_clients`)
Mỗi thiết bị có **owner** (Account Owner sở hữu) và **assigned_user** (child được gán, 1 robot = 1 user).

```sql
CREATE TABLE devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    assigned_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    product_id UUID REFERENCES products(id) ON DELETE SET NULL,
    device_hw_id VARCHAR(64),         -- UUID phần cứng đọc từ BLE (0xFF0A)
    mac_address VARCHAR(17) UNIQUE NOT NULL,
    label VARCHAR(100),
    serial_number VARCHAR(64),        -- PTALK-ROBOT-XXXX
    firmware_version VARCHAR(32),
    app_version VARCHAR(32),
    build_number VARCHAR(32),
    model VARCHAR(64),
    device_type INTEGER DEFAULT 1,    -- 1=PTalk Robot, 2=KidMentor Tablet
    connection_type INTEGER DEFAULT 0, -- 0=BLE, 1=WiFi, 2=MQTT
    status VARCHAR(16) DEFAULT 'offline',
    last_seen_at TIMESTAMPTZ DEFAULT now(),
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE mqtt_clients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID REFERENCES devices(id) ON DELETE CASCADE,
    client_id VARCHAR(128) UNIQUE NOT NULL,
    broker_url VARCHAR(255),
    username VARCHAR(128),
    status VARCHAR(16) DEFAULT 'disconnected',
    connected_at TIMESTAMPTZ,
    disconnected_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now()
);
```

### E. Bảng Telemetry (`device_heartbeats`, `connection_events`)

```sql
CREATE TABLE device_heartbeats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    ip_address VARCHAR(45),
    rssi INTEGER,               -- Cường độ WiFi (dBm)
    cpu_usage REAL,             -- % CPU
    memory_usage REAL,          -- % RAM
    uptime_seconds BIGINT,
    firmware_version VARCHAR(32),
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE connection_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID REFERENCES devices(id) ON DELETE SET NULL,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    event_type VARCHAR(32) NOT NULL, -- 'connected','disconnected','error','ota_start','ota_complete','ota_failed'
    ip_address VARCHAR(45),
    details TEXT,                      -- JSON metadata
    created_at TIMESTAMPTZ DEFAULT now()
);
```

### F. Bảng Content & Quota (`questions`, `quotas`, `quota_violations`)

```sql
CREATE TABLE questions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id UUID REFERENCES devices(id) ON DELETE SET NULL,
    question_text TEXT NOT NULL,
    answer_text TEXT,
    category VARCHAR(50),           -- 'math','science','health','general'
    status VARCHAR(16) DEFAULT 'pending',
    sentiment VARCHAR(16) DEFAULT 'neutral',
    created_at TIMESTAMPTZ DEFAULT now(),
    answered_at TIMESTAMPTZ
);

CREATE TABLE quotas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    resource_type VARCHAR(50) NOT NULL, -- 'api_requests','chat_messages','questions','ota_updates'
    max_allowed INTEGER NOT NULL,
    current_used INTEGER DEFAULT 0,
    period VARCHAR(16) DEFAULT 'daily',
    period_start DATE DEFAULT CURRENT_DATE,
    updated_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(user_id, resource_type, period)
);

CREATE TABLE quota_violations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quota_id UUID REFERENCES quotas(id) ON DELETE SET NULL,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    resource_type VARCHAR(50) NOT NULL,
    attempted_value INTEGER,
    limit_value INTEGER,
    action_taken VARCHAR(32) DEFAULT 'blocked',
    created_at TIMESTAMPTZ DEFAULT now()
);
```

### G. Bảng Chat (`conversation_logs`) — Giữ nguyên
```sql
CREATE TABLE conversation_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender VARCHAR(16) NOT NULL,
    message TEXT NOT NULL,
    sentiment VARCHAR(16) DEFAULT 'neutral',
    created_at TIMESTAMPTZ DEFAULT now()
);
```

---

## 3. Yêu cầu về API (Hợp đồng dữ liệu với FastAPI Auth)

### A. Cấu trúc mã hóa và giải mã JWT
Dịch vụ FastAPI phải cung cấp token JWT sử dụng thuật toán **HS256**.
Để tránh lỗi NextAuth ở phía Frontend không hiển thị được thông tin người dùng (bị trống name, email), Token JWT được trả về khi Đăng nhập thành công **bắt buộc phải chứa** các trường payload sau:

```json
{
  "sub": "uuid-nguoi-dung-viet-tat",
  "username": "namnx",
  "email": "namnx@ctslab.net",
  "display_name": "Nam NX",
  "user_type": "child",
  "subscription_tier": "pro",
  "exp": 1779930000
}
```
*Lưu ý cho BE:* Đảm bảo các khóa `display_name` (hoặc `displayName`) và `email` không bao giờ bị trả về giá trị `null` hoặc thiếu trong Payload của JWT.

### B. Yêu cầu API Lịch sử Chat (`/api/chat`)
Thiết kế và triển khai API đáp ứng cho Frontend Web và Mobile gọi:

1. **`GET /api/chat?userId=<UUID>`**
   - Trả về danh sách hội thoại sắp xếp theo `created_at ASC` (Từ cũ đến mới).
   - Định dạng JSON trả về chuẩn:
     ```json
     {
       "chatLogs": [
         {
           "id": "uuid-log",
           "user_id": "uuid-user",
           "sender": "user",
           "message": "Hôm nay tớ được điểm 10 môn Toán!",
           "sentiment": "positive",
           "created_at": "2026-05-27T02:40:00Z"
         }
       ]
     }
     ```

2. **`POST /api/chat`**
   - Tiếp nhận dữ liệu tin nhắn mới từ thiết bị Robot hoặc ứng dụng di động gửi lên máy chủ và ghi nhận vào DB.
   - Nhận payload dạng:
     ```json
     {
       "userId": "uuid-user",
       "sender": "user",
       "message": "Nội dung cuộc trò chuyện",
       "sentiment": "positive"
     }
     ```
   - *Yêu cầu xử lý cảm xúc tự động (Nếu BE mở rộng):* Tích hợp model phân tích sắc thái cảm xúc (Sentiment Classifier) trên BE để tự động gán nhãn `positive` (tích cực), `neutral` (trung lập), hoặc `negative` (tiêu cực) trước khi INSERT vào Database.

---

## 4. Yêu cầu Cấu hình Tunnels & Bảo mật (Cloudflare / Authentik)

### A. Cấu hình Tunnel (Cloudflare Daemon)
Đảm bảo daemon Cloudflare Tunnel trên server ánh xạ chính xác các cổng dịch vụ nội bộ ra ngoài Internet:
- Domain `auth.ctslab.net` ──> Ánh xạ đến `http://localhost:8005` trên máy chủ Host.
- Domain `dashboard.ctslab.net` ──> Ánh xạ đến `http://localhost:4321` trên máy chủ Host.

### B. Tích hợp Authentik OIDC (SSO cho Thiết bị & Web)
Để hỗ trợ đăng nhập một lần (Single Sign-On) an toàn cho ứng dụng Android PTalk Mobile App và CTS Web Dashboard:
1. **Tạo Provider OIDC mới** trên cổng quản trị Authentik.
2. **Redirect URIs được phép:**
   - Web Dashboard: `https://dashboard.ctslab.net/api/auth/callback/authentik`
   - Mobile App Android: `ptalk://auth/callback` (hoặc cấu hình App Link tương đương).
3. **Cấu hình Client ID & Client Secret:** Lưu trữ bảo mật trong file `.env` của hệ thống.

---

## 5. Hướng dẫn Triển khai Nhanh cho Kỹ sư Server (Quick Deployment Guide)

Kỹ sư vận hành Server có thể khởi chạy và cập nhật nhanh các dịch vụ bằng tổ hợp lệnh sau:

### 1. Khởi động và Rebuild Web Dashboard (Next.js)
```bash
cd /home/namnx/Ptalk_project/Dashboard/dashboard
docker compose down
docker compose up -d --build
```

### 2. Kiểm tra log kết nối Cơ sở dữ liệu và API
```bash
docker logs -f dashboard-frontend
```

### 3. Backup & Phục hồi dữ liệu Chat nhanh
```bash
# Lệnh để xuất toàn bộ bảng log hội thoại ra file SQL
docker exec -t cts-dashboard-db pg_dump -U postgres -d ptalk_auth -t conversation_logs > conversation_logs_backup.sql

# Lệnh để khôi phục dữ liệu từ file backup SQL
cat conversation_logs_backup.sql | docker exec -i cts-dashboard-db psql -U postgres -d ptalk_auth
```
