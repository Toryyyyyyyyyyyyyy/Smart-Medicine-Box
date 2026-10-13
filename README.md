# Smart Medicine Box

A medication-reminder application. It consists of a native Android client and a Python FastAPI backend. Medication data is end-to-end encrypted; the backend stores only ciphertext. A family remote-control mode lets relatives monitor and record doses without signing in with the user's account, and a protected admin console manages the backend deployment.

This project has been officially released. Not all bugs are guaranteed to be fixed; if you find a bug, please report it in [Issues](https://github.com/Toryyyyyyyyyyyyyy/Smart-Medicine-Box/issues). The statements below describe behavior verified against the current source code.

**Author:** Tory_zy Tu
**Repository:** https://github.com/Toryyyyyyyyyyyyyy/Smart-Medicine-Box

---

# Table of Contents

1. [Feature Guide](#1-feature-guide)
2. [Architecture](#2-architecture)
3. [Installation and Configuration](#3-installation-and-configuration)
4. [Security Model](#4-security-model)
5. [Data and Privacy](#5-data-and-privacy)
6. [Known Limitations](#6-known-limitations)
7. [License](#7-license)

---

# 1. Feature Guide

## 1.1 Navigation

The Android app has three bottom-navigation tabs:

- **Medicines** — medicine list, today's dose buttons, pull-to-refresh (enabled only in owner mode with a server configured, or during a family remote session).
- **Records** — medication records, selection, deletion, and undo.
- **Me** — account, family control, recycle bin, server address, language, font size, on-time threshold, permission management, test alarm, and copyright information.

The app can be used fully offline without an account; login and server features are optional.

## 1.2 Medicines and Schedules

- **Adding/editing**: name, dosage (optional; empty dosage displays "as prescribed"), repeat mode (**by weekday** or **every N days**), up to 6 daily time slots, remaining stock, notes (max 50 characters), and alert mode (sound / vibration / custom video).
- **Deleting** a medicine moves it to the recycle bin (see 1.6), where it can be restored within 7 days.
- **Interval mode**: if no anchor day is set, the schedule is treated as every day; otherwise doses fire only on days where `(today - anchor) % N == 0`.

## 1.3 Alarms and Reminders

- At each scheduled time the app fires a full-screen alarm popup (sound / vibration / custom cropped video), including on the lock screen when the required permissions are granted.
- **Snooze** re-schedules the same dose 5 minutes later.
- The alarm popup displays the medicine name, dosage (or "as prescribed"), notes (max two lines), and a stock warning when stock is zero.
- The app runs a foreground service to keep alarms working, and registers a boot receiver to restore alarms after reboot. On Huawei/HarmonyOS devices the system must allow auto-start (Phone Manager > App launch), otherwise the boot broadcast is not delivered.
- Permissions needed for reliable reminders: notifications, exact alarms, full-screen notifications (Android 14+), overlay (Huawei/HarmonyOS lock-screen popup), background pop-ups (Huawei/HarmonyOS proprietary permission, cannot be detected programmatically), and battery-optimization exemption. The permission-management screen lists each permission with its current status where detectable.

## 1.4 Dose Handling Rules

- **Take**: records the dose with `takenTime = the moment of tap`, decrements stock by 1, and prevents a repeat record for the same dose (except after an undo).
- **Status labels** compare the actual time with the scheduled time against a configurable on-time threshold (30 / 60 / 120 / 180 minutes, default 60):
  - within the threshold: `taken_on_time`;
  - earlier than the threshold: `taken_early`;
  - later than the threshold: `taken_late`.
- **Early doses**: the main dose button corresponds to the earliest unhandled dose of today. The first slot of the day may be taken early; after an early take, the button is disabled until that slot's original scheduled time passes, then it unlocks and points to the next slot. The threshold affects only the status label, never button unlocking.
- **Skip (postpone)**: the alarm popup's "not now" writes an internal `pending` entry (hidden from the records list) rather than a real record. At midnight, pending entries of the previous day become real `missed` records with no taken time. The same dose cannot create duplicate pending/missed entries.
- **Missed doses and makeup**: if "not now" on the alarm popup is clicked, the missed doses appear as "HH:MM missed" buttons on the medicine page. The main button first serves the earliest pending/missed dose from today onward; individual buttons can also be taken directly. Taking a makeup dose decrements stock once.
- **Undo**: only taken records of *today* may be undone (on the family side, "today" is judged by the controlled user's time, never the family device's clock). Undoing restores stock (+1). If the scheduled time has already passed, a missed-dose button is created instead of restoring the alarm; otherwise the dose returns to unhandled and the alarm is restored. Editing a medicine's time slots or moving it to the recycle bin freezes undo for all existing records of that medicine (a record-level `undoable` flag).
- **Records deletion**: soft deletion; deleted records disappear from the list without affecting stock, buttons, or alarms.

## 1.5 Recycle Bin

- Deleting a medicine moves it to the recycle bin; entries not restored within **7 days are purged automatically**.
- Restoring a medicine re-initializes its dose state (today's pending/missed buttons are cleared, previous progress is not inherited), but its historical records are kept and remain non-undoable.
- Family remote sessions with view-only enabled hide the recycle-bin entry; the family side has no permanent-delete action.

## 1.6 Family Remote Control

- The controlled user enables family control in Account and sets a **family-control password** (separate from the login password).
- A family member enters the user's 8-digit **user ID** and the family-control password on the family-access screen — no login account is required. `view-only` mode, when enabled, restricts the session to viewing.
- **Connection gate**: the controlled user's device must have heartbeated to the server within the last **45 minutes**; otherwise family access is refused.
- All family-side time logic (status labels, makeup, "today" judgments, missed-button generation) uses the **controlled user's time** reconstructed as `server time + clock offset` uploaded by the controlled device. The family device's own clock is not used.
- Supported family operations: take dose, makeup, undo, delete records, edit medicines. There is no skip/snooze entry on the family side.
- Family edits synchronize to the controlled device through the encrypted vault; conflicts within the 45-minute heartbeat window resolve in favor of the family side.

## 1.7 Account System

- Registration requires a display name; the server issues a unique 8-digit user ID used for login.
- Login uses challenge-response proofs derived from a client-side PBKDF2 password hash — the plaintext password never leaves the device.
- **Single-device login**: each login increments a server-side `token_version`; older tokens are rejected (CAS), so at most one device session is valid at a time.
- Logout only clears the login state; local medicines, records, and settings are retained.
- Account deletion removes the server account and all server-side data, and keeps local data untouched.

## 1.8 End-to-End Encryption and Synchronization

- Medicines, records, and custom media are encrypted with AES-256-GCM on the device using keys derived from the login password (`dataKey`/`mediaKey`); the server stores only ciphertext.
- Family access works via key wrapping: the family password wraps the login-derived keys, so family members decrypt locally without the login password.
- Synchronization runs every 15 minutes (WorkManager) and on demand; the client pushes local changes and pulls remote ones with version guards and a three-way merge on reconnect. When offline, changes stay local and are pushed automatically once the network returns.
- Family history (list of previously used family-access credentials, encrypted) also syncs through the vault.

## 1.9 Admin Console

A protected management website at `/admin` (also reachable at the root path `/`):

- **Initial credentials**: `Admin` / `123456` (or values from environment variables). These apply **only at first initialization**; afterwards passwords are changed in the console. Changing environment variables later does not modify the existing admin account.
- **Authentication**: bcrypt password hashing, login rate limiting, a 12-hour cookie session with server-side revocation (version check), CSRF protection on mutating endpoints.
- **Functions**: server overview statistics, user list (metadata only — never ciphertext, verifiers, or wrapped keys), force user sign-out (invalidates the user's app session), admin password change, LAN pairing-code generation, and an integrated API documentation tab (Swagger UI).
- **Default-password warning**: a banner is shown until the default password is changed. With `ADMIN_FORCE_PASSWORD_CHANGE=true`, user-management functions are blocked until then.
- **Password reset**: no remote reset exists; run `python cli.py reset-admin` on the server host.
- Admin credentials are entirely separate from app user accounts and family tokens: none can be used as the other.

## 1.10 Transport Security and Server Pairing

- The app accepts **HTTPS addresses only**; plain HTTP is rejected at the settings screen and in the network layer.
- **Public deployment**: Caddy terminates TLS with automatically renewed Let's Encrypt certificates; the backend listens on the loopback interface only.
- **LAN deployment**: the backend serves TLS directly with a per-server self-signed certificate (generated once and persisted). The app establishes trust through **one-time pairing**: run `python cli.py pair` on the server to print an 8-digit code (valid 10 minutes, single use, 5 attempts, IP rate-limited); the app proves knowledge of the code with an HMAC bound to the server certificate's SPKI fingerprint and then pins that certificate. If the server identity changes, the app refuses the connection with an explicit "server identity changed" error and requires re-pairing. Certificates are never accepted silently, and the app never falls back to HTTP.

# 2. Architecture

```
Android app (Kotlin)
 ├─ Room (local medicines/records)        ── offline-first, alarms work without server
 ├─ AlarmManager + foreground service     ── scheduled reminders, snooze, boot restore
 ├─ OkHttp/Retrofit (HTTPS only)          ── all server traffic
 └─ AES-GCM vault codec                   ── encrypts data before upload

Backend (FastAPI, Python)
 ├─ SQLite (single file, backend/medicine_box.db)
 ├─ Routers: /api/auth, /api/vault, /api/family*, /api/admin, /api/pair
 ├─ Admin console (/admin, static HTML + admin API)
 └─ Server identity: self-signed TLS certificate for LAN mode

Public deployment topology:
 Android app ──HTTPS── Caddy (Let's Encrypt) ──HTTP on loopback── FastAPI
LAN deployment topology:
 Android app ──HTTPS (pinned self-signed cert)── FastAPI (TLS on port 8000)
```

Three authentication mechanisms exist and are strictly separate:

1. **App user authentication** — challenge-response proofs over client-derived PBKDF2 hashes; grants access to the user's own data.
2. **Family-control authorization** — a password-derived verifier plus HMAC proofs; grants scoped access to the controlled user's data without an account.
3. **Admin console authentication** — bcrypt password plus a versioned cookie session; protects the management website only.

**Offline vs online**: adding/editing medicines, alarms, and local dose recording work offline. Login, family access, and synchronization require the server. Offline changes are pushed automatically on reconnect.

# 3. Installation and Configuration

## 3.1 Prerequisites

- Backend: Python 3.10+ (verified with 3.14), `pip`.
- Android: Android Studio, JDK 17, Android SDK (minSdk 26, targetSdk 34).

## 3.2 Backend Setup

```bash
cd backend
pip install -r requirements.txt
python main.py
```

On first start the backend creates the SQLite database and tables, generates the JWT signing key (stored in `backend/.secret_key`), seeds the admin account, and (LAN mode) generates the server identity certificate in `backend/server_identity/`.

Environment variables (see `backend/.env.example`, copy to `backend/.env`):

| Variable | Default | Purpose |
|---|---|---|
| `DEPLOY_MODE` | `lan` | `lan` = TLS on 0.0.0.0:8000 with pairing; `public` = loopback only, Caddy terminates TLS |
| `SECRET_KEY` | auto-generated | JWT signing key; rotating it invalidates all issued tokens |
| `HOST` / `PORT` | lan: 0.0.0.0 / public: 127.0.0.1; 8000 | listen address and port |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `Admin` / `123456` | initial admin credentials, **first initialization only** |
| `ADMIN_TOKEN_EXPIRE_HOURS` | 12 | admin session lifetime |
| `ADMIN_FORCE_PASSWORD_CHANGE` | false | block user management until the default password is changed |
| `TLS_CERT_FILE` / `TLS_KEY_FILE` | server_identity/identity.crt / .key | self-signed certificate paths |

Server CLI commands:

```bash
python cli.py pair           # print a one-time LAN pairing code
python cli.py fingerprint    # print the server certificate fingerprint
python cli.py reset-admin    # reset the admin account to the initial credentials
```

Public deployment: point a domain at the server, copy `Caddyfile.example` to `Caddyfile` (set your domain), run `caddy run --config Caddyfile`, and start the backend with `DEPLOY_MODE=public`. Only ports 80/443 should be exposed. (The Caddy/Let's Encrypt flow has not been tested against a real domain.)

## 3.3 Android Client

1. Open `android-app/` in Android Studio and build (`:app:assembleDebug`).
2. Install the APK on the device.
3. In the app: **Me > Server**, enter the server's HTTPS address:
   - emulator: `https://10.0.2.2:8000`
   - LAN device: `https://<server-LAN-IP>:8000`
   - public deployment: `https://<your-domain>`
4. For a LAN server, follow the pairing prompt and enter the code printed by `python cli.py pair`. Public servers with a valid certificate need no pairing.
5. Register or log in (optional — the app also works offline).

# 4. Security Model

Implemented and verifiable mechanisms:

- **Transport**: all app traffic is HTTPS. Public: system trust chain (Let's Encrypt). LAN: pinned self-signed certificate established by one-time pairing. HTTP addresses are rejected.
- **Server identity**: public mode relies on the CA system; LAN mode pins the server's SPKI fingerprint after pairing. Identity changes fail closed with an explicit prompt.
- **Password storage (server side)**: login passwords exist only as client-derived PBKDF2 hashes; family passwords exist only as a domain-separated verifier (never transmitted in plaintext); the admin password is stored as a bcrypt hash. The server never stores plaintext passwords.
- **Access control**: user tokens, family tokens, and the admin cookie session are mutually exclusive mechanisms; each endpoint enforces its required credential server-side. Anonymous endpoints are limited to `/`, `/api/health`, the app login/register challenge endpoints, the family-access challenge endpoints, and the rate-limited pairing endpoint.
- **Data confidentiality**: end-to-end encryption (AES-256-GCM) means the server, including its administrator, cannot read user data.

Known security limitations (not fixed, documented only):

- This is a **verifier model**, not OPAQUE: with a copy of the database, an attacker can brute-force weak passwords offline. Use strong passwords.
- In-memory login rate limiters reset on server restart.
- The admin session is single-session: a new login invalidates the previous one.
- `android:allowBackup="true"` means tokens and wrapped keys may be included in system backups.
- Family-history credentials are stored locally in plaintext SharedPreferences.
- The Android release build is signed with the debug keystore (development configuration).

# 5. Data and Privacy

- **On the device**: medicines, records, settings, tokens, and wrapped keys (Room database + SharedPreferences).
- **On the server**: ciphertext blobs and encrypted media only, plus account metadata (user ID, display name, family-control flags, timezone, clock offset, screen aspect, threshold, heartbeat time) and authentication material (salts, password hashes/verifiers, wrapped family key).
- Data is transmitted to the server only during login, synchronization, family access, and heartbeat operations. The admin console shows aggregate counts and account metadata, never decrypted data.

# 6. Known Limitations

- The Caddy/Let's Encrypt public-deployment flow has not been tested with a real domain.
- Only Huawei/HarmonyOS devices get built-in auto-start guidance; other Chinese ROMs (Xiaomi/OPPO/vivo) also manage auto-start but are not detected by the app.
- The backend database is a single SQLite file; concurrent write contention is handled with retries, but there is no multi-process deployment or backup tooling.
- Some behaviors (e.g., CORS `allow_origins=["*"]` with credentials, admin login without CSRF on the login endpoint itself) are known trade-offs of the current implementation.
- The project is a released version, but not all bugs are guaranteed to be fixed: there are no automated UI tests, and some paths (real-device multi-phone, Windows service autostart, browser-specific cookie behavior) are verified only by inspection or manual testing. Bug reports are welcome in [Issues](https://github.com/Toryyyyyyyyyyyyyy/Smart-Medicine-Box/issues).

# 7. License

This project is released under an **MIT-based license with an additional URL-retention condition** (not the unmodified MIT License): redistribution must retain the original project URL https://github.com/Toryyyyyyyyyyyyyy/Smart-Medicine-Box in copies or substantial portions of the software. See [LICENSE](LICENSE) for the full text.

Copyright (c) 2026 Tory_zy Tu

Third-party libraries and assets are governed by their own licenses, which are not covered by this project's copyright notice.

---

---

# 智能药箱（Smart Medicine Box）

一款用药提醒应用：原生 Android 客户端 + Python FastAPI 后端。药品数据端到端加密，服务器只存密文；家属远程控制模式让家属无需登录本人账号即可查看和记录服药；受保护的管理网站用于运维后端。

本项目已正式发布。不保证所有缺陷均已修复；如发现 Bug，请到 [Issues](https://github.com/Toryyyyyyyyyyyyyy/Smart-Medicine-Box/issues) 反馈。以下描述均基于对当前源代码的核实。

**作者：** Tory_zy Tu
**仓库地址：** https://github.com/Toryyyyyyyyyyyyyy/Smart-Medicine-Box

---

# 目录

1. [功能指南](#一功能指南)
2. [架构](#二架构)
3. [安装与配置](#三安装与配置)
4. [安全模型](#四安全模型)
5. [数据与隐私](#五数据与隐私)
6. [已知限制](#六已知限制)
7. [许可证](#七许可证)

---

# 一、功能指南

## 1.1 界面导航

App 为三栏底部导航：

- **药品** — 药品列表、今日服药按钮、下拉刷新（仅本人模式配置服务器后、或家属远程会话中可用）。
- **记录** — 服药记录：查看、多选删除、撤销。
- **我的** — 账号、家属控制、回收站、服务器地址、语言、字体大小、按时服药阈值、权限管理、测试闹钟、版权信息。

无需账号即可完全离线使用；登录与服务器功能均为可选。

## 1.2 药品与服药计划

- **添加/编辑**：名称、剂量（可留空，空剂量显示「按医嘱」）、重复方式（按周几 / 每隔 N 天）、最多 6 个时间点、剩余库存、备注（最多 50 字）、提醒模式（铃声 / 震动 / 自定义视频）。
- **删除**药品进入回收站（见 1.6），7 天内可恢复。
- **间隔模式**：未设锚点日期时按每天处理；否则仅当 `(今天-锚点) % N == 0` 的日子提醒。

## 1.3 闹钟与提醒

- 到点全屏弹窗提醒（铃声 / 震动 / 自定义裁剪视频），权限齐备时锁屏亦可弹出。
- **稍后提醒**：5 分钟后再次提醒同一剂。
- 弹窗显示药品名、剂量（空则「按医嘱」）、备注（最多两行）、库存为 0 时的缺药警告。
- 前台服务保持闹钟运行；开机广播接收器在重启后恢复全部闹钟。华为/鸿蒙设备须在系统「启动管理」中允许自启动，否则收不到开机广播。
- 可靠提醒所需权限：通知、精确闹钟、全屏通知（Android 14+）、悬浮窗（华为/鸿蒙锁屏弹窗）、后台弹窗（华为/鸿蒙专属权限，程序无法检测状态）、忽略电池优化。权限管理界面逐项列出，可检测的权限显示当前状态，并可跳转设置。

## 1.4 服药处理规则

- **服用**：记录服药（实际时间 = 点击时刻）、库存 -1；同一剂不重复记录（撤销后除外）。
- **状态标签**按实际时间与计划时间的偏差对照可配置阈值（30 / 60 / 120 / 180 分钟，默认 60）判定：
  - 阈值内：按时服用（taken_on_time）；
  - 提前超过阈值：提前服用（taken_early）；
  - 推迟超过阈值：推迟服用（taken_late）。
- **提前服用**：主按钮对应今天最早未处理剂量。今天第一个时间点可提前服用；提前服用后按钮禁用，直到该时间点原定时间到达后解锁并指向下一剂。阈值只决定状态标签，绝不参与按钮解锁。
- **暂不服用**：弹窗点「暂不服用」写入内部 `pending` 条目（记录列表隐藏），不产生正式记录；0 点时前一天 pending 转为正式「未服用」记录（无实际时间）。同一剂不会重复生成 pending/未服用条目。
- **未服用与补服**：弹窗点「暂不服用」后药品页出现「HH:MM 未服用」按钮。主按钮优先处理今天起最早的未服用/暂不服用剂量，也可单独点指定按钮补服。补服同样库存 -1。
- **撤销**：仅「今天」的已服用记录可撤销（家属端按控制对象时间判定「今天」，家属手机本地时钟不参与）。撤销后库存 +1；若计划时间已过则生成未服用按钮（不恢复闹钟），否则剂量回到未处理、闹钟恢复。修改该药时间点或将其移入回收站会冻结其全部现存记录的撤销资格（记录级 undoable 标记）。
- **记录删除**：软删除，仅从列表消失，不影响库存/按钮/闹钟。

## 1.5 回收站

- 药品删除进回收站，超过 **7 天未恢复自动清除**。
- 恢复药品会重新初始化其服药状态（清除今天的未服用/暂不服用按钮，不继承之前进度），历史记录保留且仍不可撤销。
- 家属端「仅允许查看」会话隐藏回收站入口；家属端无永久删除操作。

## 1.6 家属远程控制

- 控制对象在账号页开启家人控制并设置**家人控制密码**（与登录密码相互独立）。
- 家属在「家属控制」页输入控制对象 8 位**用户id**与家人控制密码即可进入——无需登录账号。开启「仅允许查看」时只能查看。
- **连接门禁**：控制对象设备必须在最近 **45 分钟**内向服务器心跳过，否则拒绝家属访问。
- 家属端全部时间逻辑（状态标签、补服、「今天」判断、未服用按钮生成）使用**控制对象的时间**（服务器时间 + 控制设备上传的时钟偏移重建），家属手机本地时钟不参与。
- 家属端支持：服用、补服、撤销、删除记录、编辑药品；无「暂不服用」/稍后提醒入口。
- 家属端改动经加密 vault 同步到控制设备；45 分钟心跳窗口内的冲突以家属端为准。

## 1.7 账号体系

- 注册只需显示名，服务器分配唯一 8 位用户id 用于登录。
- 登录为挑战-应答证明（客户端本地派生 PBKDF2 哈希的 HMAC），明文密码永不上传。
- **单设备登录**：每次登录递增服务器端 token_version（CAS），旧令牌立即失效，任意时刻至多一个设备会话有效。
- 退出登录仅清除登录态，本地药品/记录/设置保留。
- 注销账号删除服务器账号及其全部数据，本地数据不动。

## 1.8 端到端加密与同步

- 药品、记录、自定义媒体在设备端用登录密码派生的密钥（dataKey/mediaKey）AES-256-GCM 加密后上传，服务器只存密文。
- 家属访问靠密钥包裹：家人密码包裹登录派生密钥，家属端本地解包解密，无需登录密码。
- 同步每 15 分钟（WorkManager）及按需触发；上传/拉取带版本守卫，重连时三方合并。离线期间改动留在本地，联网后自动推送。
- 家属访问历史（以往用过的家属凭据列表，加密）同样经 vault 同步。

## 1.9 管理网站

受保护的管理网站位于 `/admin`（根路径 `/` 亦有入口）：

- **初始凭据**：`Admin` / `123456`（或环境变量指定值）。**仅首次初始化生效**；之后在管理网站改密，改环境变量不会改动已存在的管理员账号。
- **认证**：bcrypt 密码哈希、登录限速、12 小时 Cookie 会话（服务端版本号撤销）、写接口 CSRF 防护。
- **功能**：服务器概览统计、用户列表（仅元数据——绝不返回密文/验证子/包裹密钥）、强制下线用户（其 App 会话立即失效）、修改管理员密码、生成局域网配对码、内置 API 文档（Swagger UI）标签页。
- **默认密码警告**：改密前持续显示横幅；`ADMIN_FORCE_PASSWORD_CHANGE=true` 时改密前禁用用户管理功能。
- **密码重置**：无远程重置；在服务器本机执行 `python cli.py reset-admin`。
- 管理员凭据与 App 用户账号、家属令牌完全隔离，互相不能作为对方的凭证。

## 1.10 传输安全与服务器配对

- App 只接受 **https** 地址；明文 http 在设置界面和网络层双重拒绝。
- **公网部署**：Caddy 终结 TLS（Let's Encrypt 自动续期），后端仅监听回环。
- **局域网部署**：后端直挂每台服务器独立生成并持久化的自签证书。App 通过**一次性配对**建立信任：服务器执行 `python cli.py pair` 打印 8 位配对码（10 分钟有效、单次使用、5 次尝试、IP 限速）；App 用绑定服务器证书 SPKI 指纹的 HMAC 证明码知识，验证通过后永久锁定该证书。服务器身份变化时 App 明确提示「服务器身份已改变」并断开，绝不静默接受、绝不回退 HTTP。

# 二、架构

```
Android 客户端（Kotlin）
 ├─ Room 本地库（药品/记录）             —— 离线优先，闹钟不依赖服务器
 ├─ AlarmManager + 前台服务             —— 定时提醒、稍后提醒、开机恢复
 ├─ OkHttp/Retrofit（仅 HTTPS）          —— 全部服务器通信
 └─ AES-GCM vault 编解码                —— 上传前加密

后端（FastAPI，Python）
 ├─ SQLite（单文件，backend/medicine_box.db）
 ├─ 路由：/api/auth、/api/vault、/api/family*、/api/admin、/api/pair
 ├─ 管理网站（/admin，静态页面 + 管理 API）
 └─ 服务器身份：局域网模式自签 TLS 证书

公网拓扑：App ──HTTPS── Caddy（Let's Encrypt）──回环HTTP── FastAPI
局域网拓扑：App ──HTTPS（锁定自签证书）── FastAPI（8000 端口 TLS）
```

三套认证机制严格分离：

1. **App 用户认证**——客户端派生 PBKDF2 哈希的挑战-应答证明；访问本人数据。
2. **家属控制授权**——密码派生验证子 + HMAC 证明；无需账号，限定范围访问控制对象数据。
3. **管理网站认证**——bcrypt 密码 + 版本化 Cookie 会话；仅保护管理网站。

**离线与在线**：药品增删改、闹钟、本地服药记录离线可用；登录、家属访问、同步需要服务器。离线改动在联网后自动推送。

# 三、安装与配置

## 3.1 前提

- 后端：Python 3.10+（已在 3.14 验证）、pip。
- Android：Android Studio、JDK 17、Android SDK（minSdk 26 / targetSdk 34）。

## 3.2 后端部署

```bash
cd backend
pip install -r requirements.txt
python main.py
```

首次启动自动完成：建库建表、生成 JWT 签名密钥（存于 `backend/.secret_key`）、播种管理员账号、（局域网模式）生成服务器身份证书（`backend/server_identity/`）。

环境变量（参考 `backend/.env.example`，复制为 `backend/.env`）：

| 变量 | 默认值 | 用途 |
|---|---|---|
| `DEPLOY_MODE` | `lan` | `lan`=0.0.0.0:8000 直挂 TLS + 配对；`public`=仅回环，Caddy 终结 TLS |
| `SECRET_KEY` | 自动生成 | JWT 签名密钥；轮换使全部已签发令牌失效 |
| `HOST` / `PORT` | lan: 0.0.0.0 / public: 127.0.0.1；8000 | 监听地址与端口 |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `Admin` / `123456` | 管理员初始凭据，**仅首次初始化生效** |
| `ADMIN_TOKEN_EXPIRE_HOURS` | 12 | 管理员会话有效期 |
| `ADMIN_FORCE_PASSWORD_CHANGE` | false | 改密前禁用管理网站的用户管理功能 |
| `TLS_CERT_FILE` / `TLS_KEY_FILE` | server_identity/identity.crt / .key | 自签证书路径 |

服务器运维命令：

```bash
python cli.py pair           # 生成一次性局域网配对码
python cli.py fingerprint    # 打印服务器证书指纹
python cli.py reset-admin    # 重置管理员账号为初始凭据
```

公网部署：域名解析到服务器，复制 `Caddyfile.example` 为 `Caddyfile`（填自己的域名），`caddy run --config Caddyfile`，后端以 `DEPLOY_MODE=public` 启动；对外只开放 80/443。（Caddy/Let's Encrypt 流程未经真实域名实测。）

## 3.3 Android 客户端

1. 用 Android Studio 打开 `android-app/` 构建（`:app:assembleDebug`）。
2. 安装 APK 到设备。
3. App 内「我的 → 服务器」填写 https 地址：
   - 模拟器：`https://10.0.2.2:8000`
   - 局域网真机：`https://<服务器局域网IP>:8000`
   - 公网：`https://<你的域名>`
4. 局域网服务器按配对提示输入 `python cli.py pair` 打印的配对码；公网正规证书无需配对。
5. 注册或登录（可选，App 也可纯离线使用）。

# 四、安全模型

已实现且可核实的机制：

- **传输**：App 全部流量走 HTTPS。公网走系统信任链（Let's Encrypt）；局域网走配对锁定的自签证书。明文 http 地址被拒绝。
- **服务器身份**：公网依赖 CA 体系；局域网配对后锁定服务器 SPKI 指纹。身份变化即断开并明确提示。
- **密码存储（服务器侧）**：登录密码仅为客户端派生的 PBKDF2 哈希；家人密码仅为域分离验证子（明文永不上传）；管理员密码为 bcrypt 哈希。服务器不存任何明文密码。
- **访问控制**：用户令牌、家属令牌、管理员 Cookie 会话三套机制互斥，各接口在服务端强制校验各自凭据。匿名接口仅限 `/`、`/api/health`、App 注册/登录挑战接口、家属访问挑战接口与限速的配对接口。
- **数据机密性**：端到端加密（AES-256-GCM）使服务器（含其管理员）无法读取用户数据。

已知安全限制（仅记录，未修复）：

- 属 **verifier 模型**而非 OPAQUE：数据库整体泄露时弱密码可被离线爆破。请使用强密码。
- 登录限速为进程内内存实现，服务器重启清零。
- 管理员会话为单会话：新登录会使旧会话失效。
- `android:allowBackup="true"`：令牌与包裹密钥可能随系统备份导出。
- 家属访问历史凭据以明文存于本地 SharedPreferences。
- Android 发布包使用 debug keystore 签名（开发期配置）。

# 五、数据与隐私

- **设备端**：药品、记录、设置、令牌与包裹密钥（Room 数据库 + SharedPreferences）。
- **服务器端**：仅密文 blob 与加密媒体，以及账号元数据（用户id、显示名、家属控制开关、时区、时钟偏移、屏幕比例、阈值、心跳时间）和认证材料（盐、密码哈希/验证子、包裹密钥密文）。
- 数据仅在登录、同步、家属访问与心跳时上传。管理网站只显示统计与账号元数据，看不到任何解密数据。

# 六、已知限制

- Caddy/Let's Encrypt 公网流程未经真实域名实测。
- 仅华为/鸿蒙设备有内置自启动引导；小米/OPPO/vivo 等国产 ROM 同样管理自启动，但 App 未检测。
- 后端数据库为单个 SQLite 文件；并发写冲突靠重试处理，无多进程部署与备份工具。
- 部分实现为当前取舍（如 CORS `allow_origins=["*"]` 与 credentials 并存、管理员登录接口本身无 CSRF——SameSite=Lax 缓解）。
- 已正式发布，但不保证所有缺陷均已修复：无自动化 UI 测试；真机多设备、Windows 服务自启、浏览器 Cookie 行为等仅经静态检查或手工验证。发现 Bug 请到 [Issues](https://github.com/Toryyyyyyyyyyyyyy/Smart-Medicine-Box/issues) 反馈。

# 七、许可证

本项目采用**基于 MIT 并附加「保留原始项目 URL」条款的许可证**（并非未经修改的标准 MIT 许可证）：再分发时须在副本或实质性部分中保留原始项目地址 https://github.com/Toryyyyyyyyyyyyyy/Smart-Medicine-Box 。全文见 [LICENSE](LICENSE)。

Copyright (c) 2026 Tory_zy Tu

第三方库与素材受其各自许可证约束，不在本项目版权声明范围内。
