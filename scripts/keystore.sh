#!/usr/bin/env bash
#
# keystore.sh — 统一 release 签名的跨机器分发 / 校验工具
#
# 背景: app/build.gradle.kts 的签名加载顺序是
#   1) 仓库根 keystore.properties (+ 其 storeFile 指向的 keystore 文件)
#   2) KEYSTORE_* 环境变量
#   3) 都没有 → 回退到「本机 ~/.android/debug.keystore」并打 WARNING
# 前两者都是 gitignored, 换台机器打包就会静默落到第 3 步, 签出另一把 key。
# v1.1.0 / v1.1.1 / v1.1.2 三次发布因此用了三把不同的 debug key。
#
# 本脚本把「导出 → 搭到另一台机器 → 落地 → 断言指纹一致」串成一条命令。
#
# 用法:
#   scripts/keystore.sh status
#   scripts/keystore.sh export  [-o FILE] [--passphrase-file FILE]
#   scripts/keystore.sh install <bundle> [--repo-root DIR]
#   scripts/keystore.sh sync    <user@host> [-p PORT] --remote-path DIR
#   scripts/keystore.sh verify  [--build] [--expected HEX]
#
set -euo pipefail

# 必须与 .github/workflows/ci.yml 的 EXPECTED 一致 —— 改一处就要改另一处。
DEFAULT_EXPECTED="45cec24fbdfcaccbfa7ef558fa262fda83ffc2eabba18efdb7a3487682ae4db7"
PROPS_NAME="keystore.properties"

EXPECTED="${EXPECTED_SIGNER_SHA256:-$DEFAULT_EXPECTED}"

die()  { printf '错误: %s\n' "$*" >&2; exit 1; }
info() { printf '  %s\n' "$*"; }
head_() { printf '\n== %s\n' "$*"; }

repo_root() {
  git rev-parse --show-toplevel 2>/dev/null || die "不在 git 仓库内, 请在仓库根目录执行"
}

# keytool / apksigner 都要 JVM。跟 AGENTS.md 一致, 本机默认 Homebrew JDK17。
resolve_java() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then return 0; fi
  if [[ -x /opt/homebrew/opt/openjdk@17/bin/keytool ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17
    return 0
  fi
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local h; h=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
    [[ -n "$h" && -x "$h/bin/keytool" ]] && { export JAVA_HOME="$h"; return 0; }
  fi
  command -v java >/dev/null 2>&1 || die "找不到 JVM, 请先设置 JAVA_HOME"
}

keytool_bin() {
  resolve_java
  # 必须优先 $JAVA_HOME/bin —— PATH 里的 /usr/bin/keytool 是 macOS stub,
  # 会打印 "Unable to locate a Java Runtime" 并失败, 让指纹解析成空串。
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then
    printf '%s' "$JAVA_HOME/bin/keytool"
    return 0
  fi
  local kt
  kt=$(command -v keytool) || die "找不到 keytool"
  printf '%s' "$kt"
}

# properties 取值: cut -d= -f2- 保证值里含 '=' 也不截断
props_get() {
  local f="$1" k="$2"
  [[ -f "$f" ]] || return 1
  grep -E "^${k}=" "$f" | head -n1 | cut -d= -f2-
}

# 打印 keystore 的证书 SHA-256 (小写、去冒号)。-J-Duser.language=en 锁定输出格式,
# 否则本地化后的 keytool 会把 "SHA256:" 翻译掉, 解析就废了。
cert_fingerprint() {
  local store="$1" pass="$2" alias="$3" kt out
  kt=$(keytool_bin) || return 0
  # 整条管道吞错并恒返 0: 调用方靠「结果为空」判断失败。
  # 否则 keytool 非零 + pipefail + set -e 会让 $(...) 赋值直接把脚本静默干掉,
  # 连 "读取证书失败" 都来不及打。
  out=$(
    printf '%s\n' "$pass" | "$kt" -J-Duser.language=en -J-Duser.country=US \
      -list -v -keystore "$store" -alias "$alias" 2>/dev/null \
      | awk '$1=="SHA256:"{print $2; exit}' \
      | tr -d ':' \
      | tr 'A-F' 'a-f' 2>/dev/null || true
  )
  printf '%s' "$out"
}

# 当前生效的签名配置 (与 gradle 同优先级: properties → env)
current_signing() {
  local root="$1" props="$root/$PROPS_NAME" store pass alias
  store=$(props_get "$props" storeFile || true)
  pass=$(props_get "$props" storePassword || true)
  alias=$(props_get "$props" keyAlias || true)
  [[ -z "$store" ]] && store="${KEYSTORE_PATH:-}"
  [[ -z "$pass"  ]] && pass="${KEYSTORE_PASSWORD:-}"
  [[ -z "$alias" ]] && alias="${KEY_ALIAS:-}"
  SIGNING_STORE="$store"
  SIGNING_PASS="$pass"
  SIGNING_ALIAS="$alias"
  if [[ -z "$store" || -z "$pass" || -z "$alias" ]]; then
    UNIFIED_READY=0
  elif [[ ! -f "$root/$store" && ! -f "$store" ]]; then
    UNIFIED_READY=2   # 配置齐但文件缺失
  else
    UNIFIED_READY=1
  fi
}

find_apksigner() {
  local c
  for c in "${ANDROID_HOME:-}/build-tools"/*/apksigner \
           /opt/homebrew/share/android-commandlinetools/build-tools/*/apksigner \
           "$HOME"/Library/Android/sdk/build-tools/*/apksigner; do
    [[ -n "${c:-}" && -x "$c" ]] && { printf '%s' "$c"; return 0; }
  done
  command -v apksigner 2>/dev/null || return 1
}

# ---------------------------------------------------------------- status
cmd_status() {
  local root=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --repo-root) root="$2"; shift 2 ;;
      *) die "status: 未知参数 $1" ;;
    esac
  done
  [[ -n "$root" ]] || root=$(repo_root)
  head_ "签名状态 — $root"
  current_signing "$root"
  if [[ "$UNIFIED_READY" -eq 0 ]]; then
    warn_missing || true
    return 1
  elif [[ "$UNIFIED_READY" -eq 2 ]]; then
    die "keystore.properties 已配置, 但 storeFile '${SIGNING_STORE}' 不存在 — 跑 install"
  fi

  local store_abs="$SIGNING_STORE"
  [[ "$store_abs" = /* ]] || store_abs="$root/$SIGNING_STORE"

  info "storeFile : $SIGNING_STORE"
  info "keyAlias  : $SIGNING_ALIAS"
  info "来源      : $([[ -f "$root/$PROPS_NAME" ]] && echo "keystore.properties" || echo "KEYSTORE_* 环境变量")"

  local fp; fp=$(cert_fingerprint "$store_abs" "$SIGNING_PASS" "$SIGNING_ALIAS")
  [[ -n "$fp" ]] || die "读取证书失败 (密码或 alias 不对)"
  info "指纹 SHA-256: $fp"
  if [[ "$fp" == "$EXPECTED" ]]; then
    info "与期望一致 ✅  (= ci.yml EXPECTED)"
  else
    printf '  与期望不一致 ❌\n     期望: %s\n     实际: %s\n' "$EXPECTED" "$fp" >&2
    return 1
  fi
}

warn_missing() {
  cat >&2 <<EOF
  keystore.properties / KEYSTORE_* 未配置 — 打包会回退到本机 debug key,
  签出来的包与已发布版本签名不同 (v1.1.0/1.1.1/1.1.2 就是三把不同的 key)。

  在已配好的机器上执行:
    scripts/keystore.sh export -o /tmp/ks.tar.gz
    # 拷到目标机后:
    scripts/keystore.sh install /tmp/ks.tar.gz
EOF
  return 1
}

# ---------------------------------------------------------------- export
cmd_export() {
  local out="" passfile="" root; root=$(repo_root)
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -o|--output) out="$2"; shift 2 ;;
      --passphrase-file) passfile="$2"; shift 2 ;;
      *) die "export: 未知参数 $1" ;;
    esac
  done
  [[ -n "$out" ]] || out="sshinjector-keystore-$(date +%Y%m%d).tar.gz"

  current_signing "$root"
  [[ "$UNIFIED_READY" -eq 1 ]] || die "本地签名未就绪, 无可导出 (先跑 status)"

  local store_abs="$SIGNING_STORE"
  [[ "$store_abs" = /* ]] || store_abs="$root/$SIGNING_STORE"
  local rel="${store_abs#"$root"/}"
  if [[ "$rel" == "$store_abs" ]]; then
    die "storeFile ($SIGNING_STORE) 不在仓库内, 无法打包成可移植路径"
  fi

  head_ "导出签名材料 → $out"
  local tmp; tmp=$(mktemp -d)
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" EXIT
  cp -p "$root/$PROPS_NAME" "$tmp/$PROPS_NAME"
  mkdir -p "$tmp/$(dirname "$rel")"
  cp -p "$store_abs" "$tmp/$rel"
  chmod 600 "$tmp/$PROPS_NAME" "$tmp/$rel"
  tar -czf "$tmp/bundle.tar.gz" -C "$tmp" "$PROPS_NAME" "$rel"

  if [[ -n "$passfile" ]]; then
    [[ -f "$passfile" ]] || die "口令文件不存在: $passfile"
    command -v openssl >/dev/null 2>&1 || die "需要 openssl 才能加密"
    openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -salt -md sha256 \
      -pass "file:$passfile" -in "$tmp/bundle.tar.gz" -out "$out"
    chmod 600 "$out"
    info "已加密 (aes-256-cbc / pbkdf2 100000)"
  else
    install -m 600 "$tmp/bundle.tar.gz" "$out" 2>/dev/null || { cp "$tmp/bundle.tar.gz" "$out"; chmod 600 "$out"; }
    warn_plaintext
  fi
  info "包含: $PROPS_NAME + $rel"
  info "安装: scripts/keystore.sh install $out"
}

warn_plaintext() {
  printf '  ⚠ 打包为明文 (含 storePassword/keyPassword) — 文件权限已设 600,\n' >&2
  printf '    传完请在两台机器上都删掉。要加密加 --passphrase-file <口令文件>。\n' >&2
}

# ---------------------------------------------------------------- install
cmd_install() {
  local bundle="" root="" passfile=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --repo-root) root="$2"; shift 2 ;;
      --passphrase-file) passfile="$2"; shift 2 ;;
      -*) die "install: 未知参数 $1" ;;
      *) bundle="$1"; shift ;;
    esac
  done
  [[ -n "$bundle" ]] || die "用法: install <bundle> [--repo-root DIR]"
  [[ -f "$bundle" ]] || die "找不到文件: $bundle"
  [[ -n "$root" ]] || root=$(repo_root)

  head_ "安装签名材料 → $root"
  local tmp; tmp=$(mktemp -d)
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" EXIT

  local tarball="$bundle"
  if head -c 8 "$bundle" | grep -q 'Salted__'; then
    command -v openssl >/dev/null 2>&1 || die "bundle 已加密, 需要 openssl"
    local pp=()
    if [[ -n "$passfile" ]]; then pp=(-pass "file:$passfile"); else pp=(-pass prompt); fi
    local err
    if ! err=$(openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 -md sha256 \
        "${pp[@]}" -in "$bundle" -out "$tmp/bundle.tar.gz" 2>&1); then
      printf '解密失败 — 口令不对, 或文件已损坏。原始错误:\n%s\n' "$err" >&2
      exit 1
    fi
    tarball="$tmp/bundle.tar.gz"
    info "已解密"
  fi

  # 先校验归档成员路径, 拒绝绝对路径/路径穿越
  while IFS= read -r entry; do
    case "$entry" in
      /*|*"/../"*|../*|*/..|..)
        die "bundle 内含不安全路径: $entry"
        ;;
    esac
  done < <(tar -tzf "$tarball")

  tar -C "$tmp" -xzf "$tarball"
  [[ -f "$tmp/$PROPS_NAME" ]] || die "bundle 里没有 $PROPS_NAME"
  # 先确认 bundle 内部指纹正确, 再覆盖目标机文件 —— 避免把坏材料盖上去
  local store pass alias fp
  store=$(props_get "$tmp/$PROPS_NAME" storeFile || true)
  pass=$(props_get "$tmp/$PROPS_NAME" storePassword || true)
  alias=$(props_get "$tmp/$PROPS_NAME" keyAlias || true)
  [[ -n "$store" && -n "$pass" && -n "$alias" ]] || die "bundle 内 $PROPS_NAME 缺字段"
  case "$store" in
    /*|*"/../"*|../*|*/..|..)
      die "bundle 内 storeFile 非法(必须是仓库内相对路径): $store"
      ;;
  esac
  [[ -f "$tmp/$store" ]] || die "bundle 内缺少 keystore 文件: $store"
  fp=$(cert_fingerprint "$tmp/$store" "$pass" "$alias")
  [[ -n "$fp" ]] || die "bundle 内 keystore 读不出证书 (密码/alias 错)"
  if [[ "$fp" != "$EXPECTED" ]]; then
    printf 'bundle 指纹与期望不符, 拒绝安装\n  期望 %s\n  实际 %s\n' "$EXPECTED" "$fp" >&2
    exit 1
  fi
  info "bundle 指纹校验通过: $fp"

  [[ -d "$root" ]] || die "目标目录不存在: $root"
  mkdir -p "$root/$(dirname "$store")"
  install -m 600 "$tmp/$PROPS_NAME" "$root/$PROPS_NAME" 2>/dev/null || {
    cp "$tmp/$PROPS_NAME" "$root/$PROPS_NAME"; chmod 600 "$root/$PROPS_NAME"; }
  install -m 600 "$tmp/$store" "$root/$store" 2>/dev/null || {
    cp "$tmp/$store" "$root/$store"; chmod 600 "$root/$store"; }
  info "已写入 $root/$PROPS_NAME"
  info "已写入 $root/$store"

  ( cd "$root" && cmd_status ) || die "安装后校验失败"
}

# ---------------------------------------------------------------- verify
cmd_verify() {
  local build=0 root=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --build) build=1; shift ;;
      --expected) EXPECTED="$2"; shift 2 ;;
      --repo-root) root="$2"; shift 2 ;;
      *) die "verify: 未知参数 $1" ;;
    esac
  done
  [[ -n "$root" ]] || root=$(repo_root)

  head_ "校验签名"
  ( cd "$root" && cmd_status ) || exit 1

  [[ "$build" -eq 1 ]] || return 0

  head_ "构建 release 并核对 APK 实际签名"
  [[ -x "$root/gradlew" ]] || die "找不到 gradlew"
  ( cd "$root" && ./gradlew assembleRelease )

  local apksigner; apksigner=$(find_apksigner) || die "找不到 apksigner (设 ANDROID_HOME?)"
  resolve_java
  local apk fp_all
  fp_all=""
  for apk in "$root"/app/build/outputs/apk/release/*.apk; do
    [[ -f "$apk" ]] || continue
    fp=$("$apksigner" verify --print-certs "$apk" 2>/dev/null | grep -oiE '\b[0-9a-f]{64}\b' || true)
    [[ -n "$fp" ]] || die "读不到签名: $apk"
    fp_all="$fp_all $fp"
  done
  [[ -n "${fp_all// /}" ]] || die "没有找到 release APK"
  fp_all=$(printf '%s\n' $fp_all | sort -u)
  info "APK signer SHA-256: $fp_all"
  if [[ "$fp_all" == "$EXPECTED" ]]; then
    info "与期望一致 ✅"
  else
    printf 'APK 签名与期望不一致 ❌\n  期望 %s\n  实际 %s\n' "$EXPECTED" "$fp_all" >&2
    exit 1
  fi
}

# ---------------------------------------------------------------- sync
cmd_sync() {
  local host="" port="22" remote="" passfile=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -p|--port) port="$2"; shift 2 ;;
      --remote-path) remote="$2"; shift 2 ;;
      --passphrase-file) passfile="$2"; shift 2 ;;
      -*) die "sync: 未知参数 $1" ;;
      *) host="$1"; shift ;;
    esac
  done
  [[ -n "$host" ]] || die "用法: sync <user@host> [-p PORT] --remote-path DIR"
  [[ -n "$remote" ]] || die "必须指定 --remote-path <目标机上的仓库根目录>"

  local root; root=$(repo_root)
  head_ "同步签名 → $host:$remote"
  local tmp; tmp=$(mktemp -d)
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" EXIT
  local bundle="$tmp/bundle.tar.gz"
  local pp=(); [[ -n "$passfile" ]] && pp=(--passphrase-file "$passfile")
  ( cd "$root" && cmd_export -o "$bundle" "${pp[@]}" )

  local sshcmd=(ssh -p "$port" -o ConnectTimeout=20 -o BatchMode=yes)
  local scpcmd=(scp -P "$port" -o ConnectTimeout=20 -o BatchMode=yes)

  "${scpcmd[@]}" "$bundle" "$host:$remote/.keystore-bundle.tmp" || die "scp bundle 失败"
  mkdir -p "$root/scripts"
  "${scpcmd[@]}" "$root/scripts/keystore.sh" "$host:$remote/scripts/keystore.sh" || die "scp 脚本失败"

  local remote_cmd="cd '$remote' && bash scripts/keystore.sh install .keystore-bundle.tmp"
  [[ -n "$passfile" ]] && remote_cmd+=" --passphrase-file '$passfile'"
  remote_cmd+=" && rm -f .keystore-bundle.tmp"
  "${sshcmd[@]}" "$host" "$remote_cmd" || die "远端安装失败"
  "${sshcmd[@]}" "$host" "cd '$remote' && bash scripts/keystore.sh status" || die "远端校验失败"
  info "同步完成 ✅"
}

# ---------------------------------------------------------------- ci-secrets
# 输出 GitHub Actions 两个必需 secrets 的值 (base64 单行), 供 Settings →
# Secrets and variables → Actions 粘贴。与 sync/install 同源, 避免手抄错。
cmd_ci_secrets() {
  local root=""; root=$(repo_root)
  current_signing "$root"
  [[ "$UNIFIED_READY" -eq 1 ]] || { warn_missing || true; return 1; }
  local store_abs="$SIGNING_STORE"
  [[ "$store_abs" = /* ]] || store_abs="$root/$SIGNING_STORE"
  [[ -f "$root/$PROPS_NAME" ]] || die "缺 $PROPS_NAME, 无法导出 properties"
  printf 'RELEASE_KEYSTORE_B64=%s\n' "$(base64 < "$store_abs" | tr -d '\n')"
  printf 'RELEASE_KEYSTORE_PROPERTIES_B64=%s\n' "$(base64 < "$root/$PROPS_NAME" | tr -d '\n')"
  printf '# 预期指纹: %s\n' "$EXPECTED" >&2
}

usage() {
  cat <<'EOF'
keystore.sh — 统一 release 签名的跨机器分发 / 校验

  status                                查看本机签名配置与证书指纹
  export  [-o FILE] [--passphrase-file F]
                                        打包 keystore.properties + keystore 文件
  install <bundle> [--repo-root DIR] [--passphrase-file F]
                                        落地到目标仓库 (先校验 bundle 指纹再覆盖)
  verify  [--build] [--expected HEX]    校验配置; --build 再跑 assembleRelease
                                        并用 apksigner 核对 APK 实际签名
  sync    <user@host> [-p PORT] --remote-path DIR [--passphrase-file F]
                                        一条命令: 导出 → scp → 远端 install+status
  ci-secrets                            输出 RELEASE_KEYSTORE_* 两个 base64 值
                                        (粘进 GitHub Settings → Secrets → Actions)

期望指纹默认 = ci.yml 的 EXPECTED, 可用 --expected / EXPECTED_SIGNER_SHA256 覆盖。
EOF
}

main() {
  local cmd="${1:-}"; shift || true
  case "$cmd" in
    status)  cmd_status "$@" ;;
    export)  cmd_export "$@" ;;
    install) cmd_install "$@" ;;
    verify)  cmd_verify "$@" ;;
    sync)    cmd_sync "$@" ;;
    ci-secrets) cmd_ci_secrets "$@" ;;
    -h|--help|help|"") usage ;;
    *) usage >&2; die "未知子命令: $cmd" ;;
  esac
}

main "$@"
