# 恢复演练步骤预案

## 前提条件
- 已停止服务（`systemctl stop toneup` 或等效进程终止）
- 备份目录中存在最近的 zip 归档文件

## 步骤

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | **停服务** | 执行 `systemctl stop toneup`（或手动终止所有 uvicorn/gunicorn 进程），确保无写入活动 | |
| 2 | **选回档** | 进入备份目录（`cd backups`），选择最近一次的 zip 文件（`ls -lt backup-*.zip | head -1`） | |
| 3 | **解压覆盖** | 将 zip 解压至数据根目录，覆盖核心文件：<br>`unzip backup-*.zip -d /path/to/data`<br>核心文件包括：`user_data.db`、`knowledge_tags.db`、`manifest.json` | |
| 4 | **启动服务** | 执行 `systemctl start toneup`（或重新启动 uvicorn/gunicorn），确保服务正常启动 | |
| 5 | **健康自检** | 使用探针检查服务状态：`GET /api/admin/health`，确认返回 200 且 JSON 中 `status` 为 `healthy` | |
| 6 | **验证登录与拉题** | 管理员账号登录系统，尝试正常拉取任务/数据，确认业务流程可用 | |
| 7 | **填写演练结果记录模板** | 使用以下模板记录演练结果：<br><br>```markdown<br>## 恢复演练记录<br><br>- **日期**：YYYY-MM-DD<br>- **触发原因**：<br>- **备份文件**：backup-*.zip<br>- **恢复时间**：<br>- **是否成功**：是 / 否<br>- **问题描述**：<br>- **改进措施**：<br>```<br><br>记录完成后提交至文档仓库或运维平台 | |