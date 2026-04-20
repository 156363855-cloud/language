# Worker Pack

这套目录是给“另一台电脑作为本地处理工作站”准备的。

它只包含两部分：

- `frontend`
  用来打开本地管理页面，地址默认是 `http://127.0.0.1:5173`
- `backend-worker`
  负责下载音频、转码、转写、翻译、上传对象存储，并把结果同步到云端 API A

云端 API A 继续放在服务器上：

- `http://43.155.234.124:3000/api`

## 目录结构

- `docker-compose.yml`
- `.env.example`
- `frontend/`
- `backend-worker/`
- `backend-runtime-worker/`
- `secrets/`

## 启动前准备

1. 安装 Docker Desktop
2. 复制环境变量文件

```bash
cp .env.example .env
```

3. 编辑 `.env`

至少需要补这些值：

- `DEEPSEEK_API_KEY`
- `APP_CLOUD_API_BASE_URL`
- `APP_STORAGE_BUCKET`
- `APP_STORAGE_OCI_NAMESPACE`
- `APP_STORAGE_OCI_REGION`
- `APP_STORAGE_OCI_TENANCY`
- `APP_STORAGE_OCI_USER`
- `APP_STORAGE_OCI_FINGERPRINT`

4. 把对象存储私钥放到：

```text
secrets/oracle-objectstorage.pem
```

## 启动

```bash
docker compose up -d --build
```

启动后：

- 前端页面：`http://127.0.0.1:5173`
- 本地 worker 健康检查：`http://127.0.0.1:8081/actuator/health`

## 这套包适合做什么

- 在另一台电脑上打开同样的管理前端
- 独立处理一批新的音频
- 处理完成后同步到同一个云端服务和对象存储

## 多台电脑一起用时的建议

可以加速，但当前更适合“人工分工”：

- 电脑 A 处理一批链接
- 电脑 B 处理另一批链接
- 不要几台机器同时处理同一个链接

因为现在还没有真正的“任务抢占锁”，如果几台电脑同时处理同一条任务，可能会重复上传和重复写入。

## 停止

```bash
docker compose down
```

## 升级到最新版本

如果主工程有更新，重新同步这个目录后执行：

```bash
docker compose up -d --build
```
