# ToneUp 上线检查清单（第 11 章基线）

| # | 检查项 | 说明 | 负责人 | 日期 |
|---|--------|------|--------|------|
| ① | **Nginx 443 反代 127.0.0.1:8000**<br>80 强跳 HTTPS | Nginx 配置将 443 端口流量反向代理到 127.0.0.1:8000，并强制将 HTTP 80 重定向到 HTTPS | | |
| ② | **Let's Encrypt 自动续期** | 配置 certbot 定时任务，确保 TLS 证书在有效期前自动续期（建议每 60 天） | | |
| ③ | **client_max_body_size 10m** | Nginx 限制请求体最大尺寸，防止大体上传耗尽资源 | | |
| ④ | **gzip + 静态与图片缓存头透传** | 开启 gzip 压缩，并确保静态资源与图片响应包含合理的 `Cache-Control` 与 `Expires` 头 | | |
| ⑤ | **systemd Restart=always、专用非 root 用户、EnvironmentFile 注入密钥、MemoryMax 护栏** | systemd 服务单元配置：`Restart=always`、非 root 用户、通过 `EnvironmentFile` 注入敏感密钥、设置 `MemoryMax` 上限 | | |
| ⑥ | **安全组仅开 22/80/443** | 阿里云/AWS 安全组仅开放 SSH (22)、HTTP (80) 与 HTTPS (443) 端口，其余一律封禁 | | |
| ⑦ | **SSH 强制密钥** | 禁用密码登录，仅允许 SSH 密钥认证，禁止 root 直连 | | |
| ⑧ | **CORS 仅允许配置清单中的前端来源** | 后端 CORS 中间件仅白名单列出的前端域名，禁止 `*` 或任意来源 | | |
| ⑨ | **/api/admin/health 作探针** | 将 `/api/admin/health` 作为健康检查探针，未通过则不视为健康 | | |
| ⑩ | **stdout 日志轮转** | 配置 logrotate 或等效机制，防止日志文件无限增长 | | |
| ⑪ | **上线前逐项核对并形成签字记录** | 逐一核对上述项，形成签字记录；**未核对项不得上线** | | |