#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_RUNTIME_DIR="${LOCAL_RUNTIME_DIR:-$PROJECT_ROOT/backend/runtime/}"
REMOTE_HOST="${REMOTE_HOST:-root@150.230.214.236}"
REMOTE_DEPLOY_DIR="${REMOTE_DEPLOY_DIR:-/root/lingualink-deploy}"
REMOTE_RUNTIME_DIR="${REMOTE_RUNTIME_DIR:-$REMOTE_DEPLOY_DIR/backend/runtime/}"
SSH_PASSWORD="${SSH_PASSWORD:-}"

if ! command -v rsync >/dev/null 2>&1; then
  echo "缺少 rsync，请先安装 rsync 后再执行。"
  exit 1
fi

if ! command -v ssh >/dev/null 2>&1; then
  echo "缺少 ssh，请先安装 ssh 后再执行。"
  exit 1
fi

run_remote_command() {
  local command="$1"

  if [ -n "$SSH_PASSWORD" ]; then
    if ! command -v expect >/dev/null 2>&1; then
      echo "检测到设置了 SSH_PASSWORD，但系统缺少 expect，无法自动输入密码。"
      exit 1
    fi

    local temp_script
    temp_script="$(mktemp)"
    cat >"$temp_script" <<EOF
#!/usr/bin/env bash
set -euo pipefail
$command
EOF
    chmod +x "$temp_script"

    local remote_script="/tmp/lingualink-sync-remote-$$.sh"

    TEMP_SCRIPT_VALUE="$temp_script" REMOTE_HOST_VALUE="$REMOTE_HOST" REMOTE_SCRIPT_VALUE="$remote_script" SSH_PASSWORD_VALUE="$SSH_PASSWORD" expect <<'EOF'
set timeout -1
spawn bash -lc "scp \"$env(TEMP_SCRIPT_VALUE)\" \"$env(REMOTE_HOST_VALUE):$env(REMOTE_SCRIPT_VALUE)\""
expect "password:"
send "$env(SSH_PASSWORD_VALUE)\r"
expect eof
EOF

    REMOTE_HOST_VALUE="$REMOTE_HOST" REMOTE_SCRIPT_VALUE="$remote_script" SSH_PASSWORD_VALUE="$SSH_PASSWORD" expect <<'EOF'
set timeout -1
spawn bash -lc "ssh \"$env(REMOTE_HOST_VALUE)\" \"bash '$env(REMOTE_SCRIPT_VALUE)'; rm -f '$env(REMOTE_SCRIPT_VALUE)'\""
expect "password:"
send "$env(SSH_PASSWORD_VALUE)\r"
expect eof
EOF

    rm -f "$temp_script"
    return
  fi

  ssh "$REMOTE_HOST" "$command"
}

run_rsync_sync() {
  if [ -n "$SSH_PASSWORD" ]; then
    if ! command -v expect >/dev/null 2>&1; then
      echo "检测到设置了 SSH_PASSWORD，但系统缺少 expect，无法自动输入密码。"
      exit 1
    fi

    LOCAL_RUNTIME_DIR_VALUE="$LOCAL_RUNTIME_DIR" REMOTE_HOST_VALUE="$REMOTE_HOST" REMOTE_RUNTIME_DIR_VALUE="$REMOTE_RUNTIME_DIR" SSH_PASSWORD_VALUE="$SSH_PASSWORD" expect <<'EOF'
set timeout -1
spawn bash -lc "rsync -avz --delete --exclude manual-test/ --exclude .DS_Store -e ssh \"$env(LOCAL_RUNTIME_DIR_VALUE)\" \"$env(REMOTE_HOST_VALUE):$env(REMOTE_RUNTIME_DIR_VALUE)\""
expect "password:"
send "$env(SSH_PASSWORD_VALUE)\r"
expect eof
EOF
    return
  fi

  rsync -avz --delete \
    --exclude 'manual-test/' \
    --exclude '.DS_Store' \
    -e ssh \
    "$LOCAL_RUNTIME_DIR" \
    "$REMOTE_HOST:$REMOTE_RUNTIME_DIR"
}

if [ ! -d "$LOCAL_RUNTIME_DIR" ]; then
  echo "本地 runtime 目录不存在: $LOCAL_RUNTIME_DIR"
  exit 1
fi

echo "开始同步本地 runtime 到云端..."
echo "本地目录: $LOCAL_RUNTIME_DIR"
echo "远端目录: $REMOTE_HOST:$REMOTE_RUNTIME_DIR"

run_rsync_sync

echo
echo "同步完成，开始重启云端后端容器..."

run_remote_command "cd \"$REMOTE_DEPLOY_DIR\" && docker compose restart backend >/dev/null && for i in \$(seq 1 24); do status=\$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}unknown{{end}}' lingualink-backend 2>/dev/null || echo missing); if [ \"\$status\" = healthy ]; then echo '云端后端健康检查已恢复'; exit 0; fi; echo \"等待后端恢复中... 当前状态: \$status\"; sleep 5; done; echo '后端重启后仍未恢复健康'; docker compose ps backend; exit 1"

echo
echo "一键同步完成。"
