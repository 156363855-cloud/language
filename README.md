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

下一阶段仍然建议继续完善：

- 任务队列
- 用户系统和权限
- 文件存储与部署配置
- 字幕导出和播放器增强

## 本地开发启动

```bash
cd backend
python3 -m venv .venv
. .venv/bin/activate
pip install -r scripts/requirements.txt
export DEEPSEEK_API_KEY=你的_key
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
# 编辑 .env，按需填入 DEEPSEEK_API_KEY
docker compose up -d --build
```

默认地址：

- Web: `http://localhost:5173`
- API: `http://localhost:8080`

常用命令：

```bash
docker compose logs -f backend
docker compose logs -f frontend
docker compose down
```

说明：

- 前端容器使用 Nginx 提供静态页面，并把 `/api` 代理到后端容器
- 后端容器内已包含 `ffmpeg`、Python 运行环境、`yt-dlp` 与 `faster-whisper` 所需依赖
- 任务运行产物会持久化到宿主机的 `backend/runtime`
- 首次转写时可能需要下载 Whisper 模型，因此首次任务会明显更慢

## 本地处理后同步到云端

如果你选择在本地电脑处理音频、再把结果同步到云服务器，可以直接使用：

```bash
chmod +x scripts/sync-runtime-to-cloud.sh
./scripts/sync-runtime-to-cloud.sh
```

如果服务器是密码登录，也可以直接一条命令自动完成：

```bash
SSH_PASSWORD='你的服务器密码' ./scripts/sync-runtime-to-cloud.sh
```

脚本默认会：

- 把本地 `backend/runtime` 同步到云端 `/root/lingualink-deploy/backend/runtime`
- 排除 `manual-test` 这类测试目录
- 自动重启云端后端容器
- 等待后端健康检查恢复

如果后面你换了服务器，也可以临时指定：

```bash
REMOTE_HOST=root@你的服务器IP ./scripts/sync-runtime-to-cloud.sh
```

## YouTube 说明

- 当前支持处理 YouTube 链接
- 短视频或讲话为主的视频更容易成功
- 当前路线是本地 Whisper 转写，所以长视频会更慢
- 如果配置了 `DEEPSEEK_API_KEY`，系统会优先用 DeepSeek 做多语言翻译，节省一部分翻译成本
- 建议先拿讲话清晰、时长较短的视频测试，再逐步尝试更长的内容

## 推荐下一步

1. 给后端补上更细的任务进度和日志
2. 使用数据库保存任务和字幕片段
3. 接入真实播放器和字幕文件导出
4. 增加账号体系与任务历史
5. 继续按安卓 App 兼容思路设计移动端接口
