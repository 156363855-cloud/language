# LinguaLink

一个面向链接媒体内容的字幕提取与翻译项目骨架。第一版目标是把下面这条主链路搭通：

1. 输入视频或音频链接
2. 创建转写与翻译任务
3. 生成带时间轴的原文字幕
4. 展示中文、日文、英文翻译
5. 在播放器界面按时间同步显示字幕

当前仓库包含两个部分：

- `backend`: Java 17 + Spring Boot 3 后端，适合直接用 IDEA 打开
- `frontend`: Vue 3 + Vite 前端，用来演示任务提交和字幕同步展示

## 当前实现

目前是一个可继续开发的第一版骨架：

- 已有任务创建接口
- 已有任务列表接口
- 已有字幕时间轴数据结构
- 前端可提交链接并查看任务
- 前端可切换中 / 日 / 英翻译
- 前端有模拟播放同步效果

当前已经接入了第一版真实能力：

- 使用 `yt-dlp` 下载媒体链接
- 使用 `ffmpeg` 提取和标准化音频
- 使用本地 `faster-whisper` 做语音转写
- 支持使用 DeepSeek 处理字幕翻译
- 如果没有配置 `DEEPSEEK_API_KEY`，会退回基础在线翻译兜底
- 核心业务元数据已经切换到 MySQL（用户、会话、文件夹、任务、生词本）
- 应用首次连上空库时，会自动从 `backend/runtime` 的旧 JSON 导入数据

下一阶段仍然建议继续完善：

- 任务队列
- 用户系统和权限
- 文件存储与部署配置
- 字幕导出和播放器增强

## 本地开发启动

```bash
docker run --name lingualink-mysql \
  -e MYSQL_DATABASE=lingualink \
  -e MYSQL_USER=lingualink \
  -e MYSQL_PASSWORD=lingualink \
  -e MYSQL_ROOT_PASSWORD=root \
  -p 3306:3306 \
  -d mysql:8.4

cd backend
python3 -m venv .venv
. .venv/bin/activate
pip install -r scripts/requirements.txt
export DEEPSEEK_API_KEY=你的_key
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/lingualink?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Tokyo&allowPublicKeyRetrieval=true&useSSL=false'
export SPRING_DATASOURCE_USERNAME=lingualink
export SPRING_DATASOURCE_PASSWORD=lingualink
mvn spring-boot:run
```

默认地址：

- API: `http://localhost:8080`

前端开发启动：

```bash
cd frontend
npm install
npm run dev
```

默认地址：

- Web: `http://localhost:5173`

## Docker Compose 部署

```bash
cp .env.example .env
# 编辑 .env，至少确认 MySQL 和 DEEPSEEK_API_KEY 配置
docker compose up -d --build
```

默认地址：

- Web: `http://localhost:5173`
- 云端 API 模拟（后端 A）: `http://localhost:8080`
- 本地处理后端（后端 B）: `http://localhost:8081`
- MySQL: `http://localhost:3306`
- 对象存储（MinIO API）: `http://localhost:9000`
- 对象存储控制台（MinIO Console）: `http://localhost:9001`

常用命令：

```bash
docker compose logs -f mysql
docker compose logs -f backend-api
docker compose logs -f backend-worker
docker compose logs -f frontend
docker compose down
```

说明：

- 前端容器使用 Nginx 提供静态页面，并把 `/api` 代理到后端 A、把 `/worker-api` 代理到后端 B
- 后端 A 是轻量 API，只负责登录、任务列表/详情、文件夹等普通接口
- 后端 B 负责下载、抽音频、转写、翻译、上传对象存储、再回写后端 A
- 后端 A 和后端 B 共享同一个 MySQL，任务/文件夹/用户数据不再以 `runtime/*.json` 作为主数据源
- 本地 MinIO 用来模拟对象存储，音频与字幕会发布到 `lingualink-assets` bucket
- `backend/runtime-api` 与 `backend/runtime-worker` 现在只保留处理临时产物和本地音频文件
- 首次转写时可能需要下载 Whisper 模型，因此首次任务会明显更慢

## 云端 / 本地拆分（推荐架构）

为了让云服务器更轻、也更安全，仓库现在支持把后端拆成两种形态：

- **云端 API 后端（server / api）**
  - 只响应前端请求：登录、任务列表/详情、文件夹、单词本等
  - 不运行 `yt-dlp/ffmpeg/whisper`，不解析音频
  - `app.processing.enabled=false` 时，`POST /api/tasks` 会拒绝创建“解析任务”
  - 音频/字幕/封面走对象存储 URL（`audioUrl/subtitleUrl/coverUrl`）

- **本地处理后端（worker）**
  - 只给你自己用：下载、转写、翻译、产出字幕
  - 处理完成后上传素材到对象存储
  - 然后调用后端 A 的 `POST /api/tasks/import` 写入元数据，让前端可播放

### 当前容器拓扑

- `frontend`: 用户唯一访问入口
- `backend-api`: 云端 API 角色（后端 A）
- `backend-worker`: 本地处理角色（后端 B）
- `mysql`: 核心业务元数据数据库
- `minio`: 对象存储

### 如何启动（云端 API）

`deploy/docker-compose.yml` 已默认设置 `APP_PROCESSING_ENABLED=false`，并使用精简版后端镜像（不包含 python/ffmpeg）。

### 同步素材到云端

对象存储和云端 API 的正式接入建议使用：

1. 本地后端 B 处理内容
2. 上传音频与字幕到对象存储
3. 调用后端 A 的 `/api/tasks/import`
4. 前端始终只连后端 A

## 关于 `runtime`

现在 `backend/runtime*` 不再承担业务主存储职责，它只保留：

- 处理中间文件，例如 `normalized.wav`、`local_chunks`
- 本地 worker 尚未清理的 `source.mp3`
- 个别需要本地回退读取的临时结果

真正的业务元数据都在 MySQL 中。如果数据库是空的，应用启动时会自动把旧版 JSON 导入进去。

## YouTube 说明

- 当前支持处理 YouTube 链接
- 短视频或讲话为主的视频更容易成功
- 当前路线是本地 Whisper 转写，所以长视频会更慢
- 如果配置了 `DEEPSEEK_API_KEY`，系统会优先用 DeepSeek 做多语言翻译，节省一部分翻译成本
- 建议先拿讲话清晰、时长较短的视频测试，再逐步尝试更长的内容

## 推荐下一步

1. 给后端补上更细的任务进度和日志
2. 把任务片段和翻译拆成更细的表结构，支持检索与统计
3. 接入真实播放器和字幕文件导出
4. 增加账号体系与任务历史
5. 继续按安卓 App 兼容思路设计移动端接口
