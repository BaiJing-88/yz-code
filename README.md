# YZ-Code

在网页中实时查看手机收到的短信验证码。

手机安装 Android App 并登录账号后，收到的新短信验证码会自动上传到服务端；在任意浏览器打开网页登录同一账号，即可查看**最新验证码**和**历史记录**，一键复制。

- 服务端端口：`3000`
- 线上访问地址：`https://frp-act.com:17435`（SakuraFrp 隧道 → 本机 3000，自动 HTTPS 为自签证书，首次访问浏览器需手动信任）
- 仅支持 Android（不支持 iOS）

## 项目结构

```
├── server/            # Node.js 服务端（零第三方依赖）+ Web 前端
│   ├── index.js       # HTTP 服务 + API + 静态文件
│   └── public/        # 网页（登录/注册 + 验证码仪表盘）
├── android/           # Android App 源码（Kotlin）
├── .github/workflows/ # GitHub Actions 自动构建 APK
└── API.md             # 接口契约
```

## 本地运行服务端

要求 Node.js ≥ 22.5（使用内置 `node:sqlite`，无需 npm install）。

```bash
cd server
npm start
# 打开 http://localhost:3000
```

数据保存在 `server/data/app.db`（SQLite，自动创建）。

## 内网穿透（frp）

本机 frpc 配置示例，将公网 `frp-act.com:17435` 转发到本机 3000：

```toml
# frpc.toml
serverAddr = "frp-act.com"
serverPort = 7000            # 以你的 frp 服务信息为准

[[proxies]]
name = "yz-code"
type = "tcp"
localIP = "127.0.0.1"
localPort = 3000
remotePort = 17435
```

## 使用流程

1. 启动服务端并保持 frp 在线。
2. 手机安装 App（见下），打开后服务器地址已预填 `https://frp-act.com:17435`，注册/登录账号。
3. 授予 App「短信」权限（接收 + 读取）。
4. 手机收到验证码短信后自动上传；浏览器打开 `https://frp-act.com:17435` 登录同一账号即可实时查看（每 4 秒自动刷新）。首次打开浏览器会提示证书不受信任（SakuraFrp 自签证书），选择「高级 → 继续前往」即可。

## 获取 APK

本仓库通过 GitHub Actions 自动构建：push 到 `main` 或在 Actions 页手动运行 **Android APK Build**，完成后在运行记录的 **Artifacts** 中下载 `yz-code-apk`（debug 签名，可直接安装）。

本地有 Android SDK 时也可以：

```bash
cd android
./gradlew assembleDebug   # 产物在 app/build/outputs/apk/debug/
```

## API

见 [API.md](API.md)。核心：`POST /api/login` 拿 token，`POST /api/codes` 上传验证码（App），`GET /api/codes` 拉取历史（网页）。

## 安全提示

- 对外链路为 HTTPS（SakuraFrp 自签证书，App 内已钉扎该证书，证书有效期至 2027-07，隧道重建或证书过期后需更新 `android/app/src/main/res/raw/sakurafrp_cert.pem` 并重新构建 APK）。
- 验证码属敏感信息，App 只上传含验证码关键词的短信提取结果，但仍请注意账号密码强度。
