# YZ-Code API 契约（服务端与 Android 端共同遵守）

Base URL（线上）: `https://frp-act.com:17435`（SakuraFrp 隧道转发到本机 3000 端口，自动 HTTPS 自签证书，Android 端已内置证书钉扎）
服务端监听: `0.0.0.0:3000`

## 认证

除注册/登录外，所有 `/api/*` 接口都需要请求头：

```
Authorization: Bearer <token>
```

token 由登录/注册接口返回，客户端自行保存（Web 端存 localStorage，Android 存 SharedPreferences），长期有效，退出登录时服务端删除。

## 接口

### POST /api/register
请求: `{"username": "...", "password": "..."}`
- 200: `{"ok": true, "token": "...", "username": "..."}`
- 400: 参数非法（用户名 2-32 位，密码 ≥ 6 位）
- 409: 用户名已存在

### POST /api/login
请求: `{"username": "...", "password": "..."}`
- 200: `{"ok": true, "token": "...", "username": "..."}`
- 401: 用户名或密码错误

### POST /api/logout（需认证）
使当前 token 失效。返回 `{"ok": true}`

### GET /api/me（需认证）
返回 `{"ok": true, "username": "..."}`

### POST /api/codes（需认证，Android App 上传验证码）
请求:
```json
{
  "code": "483920",              // 必填，提取出的验证码
  "sender": "10690000",          // 可选，短信发送方号码
  "message": "【XX】您的验证码是483920...",  // 可选，短信原文
  "received_at": 1735689600000   // 可选，收到短信的毫秒时间戳，缺省为服务器当前时间
}
```
返回: `{"ok": true, "id": 123}`

### GET /api/codes?limit=100（需认证，Web 端拉取历史）
返回该用户自己的验证码列表，按收到时间倒序（最新在前）:
```json
{"ok": true, "codes": [{"id": 1, "code": "483920", "sender": "...", "message": "...", "received_at": 1735689600000, "created_at": 1735689601000}]}
```
`limit` 可选，默认 100，最大 500。

### GET /api/codes/latest（需认证）
返回最新一条: `{"ok": true, "code": {...}}`，无记录时 `"code": null`。

### DELETE /api/codes/:id（需认证）
删除单条（只能删自己的）。返回 `{"ok": true}`

### DELETE /api/codes（需认证）
清空自己的全部历史。返回 `{"ok": true}`

## 错误格式

所有错误统一: `{"ok": false, "error": "人类可读的中文错误信息"}`，配合合适的 HTTP 状态码。

## 静态页面

`GET /` 返回 Web 前端单页应用（登录/注册 + 验证码仪表盘，前端 JS 调上述接口）。
