# 部署指南（SmartMedicineBox 后端）

本文档说明如何在自己电脑/服务器上部署后端，以及 Android 手机如何安全连接。
支持两种部署模式（`DEPLOY_MODE`）：

| 模式 | 适用场景 | 传输与身份验证 |
|---|---|---|
| `lan`（默认） | 家庭局域网：无域名、无公网 IP | 自签 TLS + 一次性配对码 + 证书锁定 |
| `public` | 公网：有域名 | Caddy 自动申请/续期 Let's Encrypt 证书，走系统信任链 |

> ⚠️ 安全红线：**任何情况下都不得关闭证书验证、接受任意证书、退回 HTTP**。
> App 已强制只接受 `https://` 地址。

---

## 一、局域网部署（Windows 电脑示例）

### 1. 安装与启动

```powershell
cd SmartMedicineBox
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r backend/requirements.txt
cd backend
python main.py
```

首次启动会自动完成：
- 生成服务器身份密钥与自签证书（`backend/server_identity/`，**重启复用同一身份**）；
- 生成签名密钥 `backend/.secret_key`（未配置 `SECRET_KEY` 时）；
- 创建管理员账号（默认 `Admin / 123456`，见下文）；
- 自动建库建表（首次升级亦自动补齐）。

启动日志会打印服务器证书指纹与管理员默认密码警告。后端监听 `https://0.0.0.0:8000`。

### 2. 防火墙（手机连不上时）

```powershell
netsh advfirewall firewall add rule name="SmartMedicineBox 8000" dir=in action=allow protocol=TCP localport=8000
```

### 3. 手机配对（每台手机一次）

1. 服务器终端执行：`python cli.py pair` → 显示 8 位配对码（10 分钟有效、仅可用一次）。
2. 手机 App：我的 → 服务器 → 填写 `https://<电脑局域网IP>:8000`（如 `https://192.168.18.10:8000`）。
3. App 检测到自签证书 → 弹出「服务器配对」→ 输入配对码 → 配对成功。
4. 之后 App 只信任该服务器的证书；重启 App/服务器都无需重新配对。

模拟器同理，地址填 `https://10.0.2.2:8000`。

### 4. 服务器身份变更（如换电脑/删除 server_identity 目录）

App 会明确提示「服务器身份已改变」并断开连接——这是安全设计，绝不静默接受。
处理方式：服务器执行 `python cli.py pair` 生成新码，App 里点「重新配对」。

### 5. 常用命令

```powershell
python cli.py pair           # 生成一次性配对码
python cli.py fingerprint    # 打印服务器证书指纹（调试）
python cli.py reset-admin    # 重置管理员账号为初始凭据（危险，需输入 yes 确认）
```

---

## 二、公网部署（Caddy 自动 HTTPS）

前提：一个域名（如 `box.example.com`）已解析到服务器 IP，且 80/443 端口可达。

### 1. 后端（只监听回环，不暴露公网）

复制 `backend/.env.example` 为 `backend/.env`：

```
DEPLOY_MODE=public
ADMIN_PASSWORD=你的初始密码
ADMIN_FORCE_PASSWORD_CHANGE=true   # 建议：改密前禁用管理网站的用户管理功能
```

启动：`python main.py`（监听 `127.0.0.1:8000`，明文不出本机）。公开 API 文档入口（/docs）已移除——文档并入管理网站 `/admin` 的「API 文档」标签页（管理员会话保护）；配对接口在公网模式下禁用（系统信任链负责身份验证）。

### 2. Caddy

安装 Caddy（<https://caddyserver.com/docs/install>），把仓库根目录的 `Caddyfile.example`
复制为 `Caddyfile`，把域名换成自己的，然后：

```powershell
caddy run --config Caddyfile
```

Caddy 自动申请并续期证书（数据持久化在 Caddy 自己的 data 目录；不要删除，否则证书反复重签）。
对外只开放 80/443；后端 8000 不对外开放。

---

## 三、管理员账号（管理网站）

- 管理网站入口：`https://<服务器地址>:8000/admin`（局域网）/ `https://<域名>/admin`（公网）。
- 初始凭据：`Admin / 123456`（或 `.env` 中 `ADMIN_USERNAME / ADMIN_PASSWORD` 指定的值）。
- **`.env` 只在首次启动（数据库还没有任何管理员时）生效**。之后修改 `.env` 不会改变已有密码——改密请登录管理网站操作。
- 默认密码仅用于初始化：启动日志和管理网站横幅都会持续警告，公网部署必须在开放前修改。
- 忘记密码：服务器本机执行 `python cli.py reset-admin`（重置为 `.env` 的初始值）。
- 管理网站与 App 账号完全隔离：管理员凭据不能用于 App 登录；App 用户/家属令牌也不能访问管理网站。管理网站只能看到统计与用户元数据，**看不到任何用户明文数据**（数据端到端加密）。

---

## 四、配置项（backend/.env，参考 .env.example）

| 变量 | 默认 | 说明 |
|---|---|---|
| `DEPLOY_MODE` | `lan` | `lan` / `public`，非法值启动即报错 |
| `SECRET_KEY` | 自动生成并存 `.secret_key` | 轮换会使所有已签发令牌失效 |
| `HOST` / `PORT` | lan=`0.0.0.0`，public=`127.0.0.1` / `8000` | 监听地址与端口 |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `Admin` / `123456` | **仅首次初始化生效** |
| `ADMIN_TOKEN_EXPIRE_HOURS` | `12` | 管理员会话有效期 |
| `ADMIN_FORCE_PASSWORD_CHANGE` | `false` | 改密前禁用管理网站用户管理功能 |
| `TLS_CERT_FILE` / `TLS_KEY_FILE` | `server_identity/identity.crt` / `.key` | 自签证书路径 |

---

## 五、已知限制与未测试项

- 本仓库未包含真实 Caddy 联调记录（需要公网域名，未实测）；Caddyfile 为标准配置。
- 登录限速为进程内内存实现，服务器重启后清零（密码本身为 bcrypt 哈希，仍是主防线）。
- 管理员会话为单会话模型：新浏览器登录会使旧会话失效（与 App 单设备登录一致）。
- 管理员登录接口未做 CSRF（SameSite=Lax 缓解）；其余写接口均有双提交 CSRF。
- Windows 服务自启（NSSM/任务计划）未实测，按标准做法即可。
