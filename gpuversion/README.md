# GPU Version

这套目录是给有 NVIDIA 显卡的 Windows / Linux 电脑准备的本地处理方案。

它会启动：

- `mysql`  
  本地元数据/任务表（与完整栈一致，Worker 必须能连上数据库才能启动）
- `frontend`  
  本地管理页面，默认访问 `http://127.0.0.1:5173`
- `backend-worker`  
  使用 GPU 做本地转写、翻译、上传对象存储，并把结果同步到云端 API A

云端 API A 继续使用：

- `http://43.155.234.124:3000/api`

## 启动前要求

1. 已安装 Docker Desktop
2. Docker 能识别 NVIDIA GPU
3. 这台机器在终端里执行 `nvidia-smi` 可以看到显卡
4. WSL 环境里已经启用 Docker 集成

## 目录准备

进入这个目录后先复制环境变量文件：

```bash
cp .env.example .env
```

然后把对象存储私钥放到：

```text
gpuversion/secrets/oracle-objectstorage.pem
```

## 推荐环境变量

默认已经给了适合 `3060 Ti 8GB` 的参数：

```env
WHISPER_MODEL=small
WHISPER_DEVICE=cuda
WHISPER_COMPUTE_TYPE=float16
```

如果显存紧张，可以改成：

```env
WHISPER_COMPUTE_TYPE=int8_float16
```

## 启动

```bash
docker compose up -d --build
```

启动后：

- 前端：`http://127.0.0.1:5173`
- Worker 健康检查：`http://127.0.0.1:8081/actuator/health`

## 如果 Docker 报 GPU 不可用

先检查：

```bash
nvidia-smi
docker run --rm --gpus all nvidia/cuda:12.3.2-base-ubuntu22.04 nvidia-smi
```

如果第二条能看到显卡信息，再启动本项目。
