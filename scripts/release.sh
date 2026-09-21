#!/usr/bin/env bash
#
# A股量化机器人 —— 一键发布脚本
#
# 功能：改版本号 → 重新打包 release → 上传 GitHub Releases → 打印永久直链
#
# 用法：
#   ./scripts/release.sh 1.4.0 "补丁说明"
#   ./scripts/release.sh 1.4.0          # 不带说明，使用默认更新日志
#
# 依赖：
#   - gradle（mise 或系统路径均可）
#   - gh CLI（已登录，具备 repo / delete_repo 权限）
#   - Android SDK（local.properties 已指向 sdk.dir）
#   - JDK 17（Android Gradle Plugin 需要；默认自动探测 mise 安装路径）
#
set -euo pipefail

# ---------- 参数 ----------
NEW_VERSION="${1:-}"
CHANGELOG="${2:-}"

if [ -z "$NEW_VERSION" ]; then
  echo "用法: $0 <版本号> [更新日志]"
  echo "  示例: $0 1.4.0 \"修复若干崩溃\""
  exit 1
fi

# ---------- 常量 ----------
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# ---------- GitHub token：优先环境变量，否则读取本地 gitignore 的 .release-token ----------
if [ -z "${GH_TOKEN:-}" ] && [ -f "$ROOT_DIR/.release-token" ]; then
  export GH_TOKEN="$(tr -d '[:space:]' < "$ROOT_DIR/.release-token")"
fi

GRADLE_FILE="$ROOT_DIR/app/build.gradle.kts"
REPO="tangtangchen23/aquant"
# 统一使用固定资产名，保证直链 /releases/latest/download/aquant-release.apk 永远指向最新版，
# 应用内 DEFAULT_UPDATE_URL 只需设置一次、每次发布无需改动。
APK_ASSET_NAME="aquant-release.apk"
BUILD_OUT="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"

# ---------- 自动探测 JDK 17（AGP 需要；本项目历史证明 Java25 会失败）----------
JDK17=""
for cand in \
  "$HOME/.local/share/mise/installs/java/17.0.2" \
  "$HOME/.mise/installs/java/17.0.2" \
  "/usr/lib/jvm/java-17-openjdk-amd64"; do
  if [ -x "$cand/bin/java" ]; then JDK17="$cand"; break; fi
done
if [ -z "$JDK17" ]; then
  # 兜底：从 gradle -version 探测当前可用 JDK 是否能构建
  if command -v gradle >/dev/null 2>&1; then
    echo "⚠️  未找到 JDK17，将使用当前 gradle 环境 JDK（若失败请设置 JDK17_HOME 指向 JDK17 目录）"
  else
    echo "❌ 未找到 gradle，请先安装 gradle"
    exit 1
  fi
fi
export JAVA_HOME="${JDK17_HOME:-$JDK17}"
if [ -n "$JAVA_HOME" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi

# ---------- 计算 versionCode（在现有基础上 +1，或手动指定 VERSION_CODE 环境变量）----------
CUR_VERSION_NAME=$(grep -o 'versionName = "[^"]*"' "$GRADLE_FILE" | head -1 | sed 's/.*"\(.*\)"/\1/')
CUR_VERSION_CODE=$(grep -o 'versionCode = [0-9]*' "$GRADLE_FILE" | head -1 | grep -o '[0-9]*')
NEW_VERSION_CODE="${VERSION_CODE:-$((CUR_VERSION_CODE + 1))}"

# ---------- 校验 & 确认 ----------
echo "========== A股量化机器人 发布准备 =========="
echo "  当前版本 : v$CUR_VERSION_NAME (versionCode=$CUR_VERSION_CODE)"
echo "  新版本   : v$NEW_VERSION  (versionCode=$NEW_VERSION_CODE)"
echo "  仓库     : $REPO"
echo "  更新日志 : ${CHANGELOG:-（使用默认文案）}"
echo "============================================="
read -r -p "确认发布？[y/N] " yes_no
if [ "$yes_no" != "y" ] && [ "$yes_no" != "Y" ]; then
  echo "已取消。"
  exit 0
fi

# ---------- 1. 改版本号 ----------
echo
echo "[1/4] 更新版本号 -> v$NEW_VERSION (versionCode=$NEW_VERSION_CODE)"
sed -i -E "s/versionCode = [0-9]+/versionCode = $NEW_VERSION_CODE/" "$GRADLE_FILE"
sed -i -E "s/versionName = \"[^\"]*\"/versionName = \"$NEW_VERSION\"/" "$GRADLE_FILE"

# ---------- 2. 打包 release ----------
echo "[2/4] 编译 release APK ..."
(
  cd "$ROOT_DIR"
  gradle clean assembleRelease -Dorg.gradle.java.home="$JAVA_HOME" --console=plain
)
if [ ! -f "$BUILD_OUT" ]; then
  echo "❌ 构建失败：未找到 $BUILD_OUT"
  exit 1
fi

# ---------- 3. 上传并发布 Release ----------
STAGE="$ROOT_DIR/build/releases"
mkdir -p "$STAGE"
APK_PATH="$STAGE/$APK_ASSET_NAME"
cp "$BUILD_OUT" "$APK_PATH"

RELEASE_NOTES="${CHANGELOG:-A股量化机器人 v$NEW_VERSION 更新。}"
if [ -z "$CHANGELOG" ]; then
  RELEASE_NOTES="## v$NEW_VERSION
- 更新内容待补
- 通过一键发布脚本打包并托管到 GitHub Releases"
fi

echo "[3/4] 创建 Release v$NEW_VERSION ..."
if gh release view "v$NEW_VERSION" --repo "$REPO" >/dev/null 2>&1; then
  echo "⚠️  Release v$NEW_VERSION 已存在，覆盖同名校对旧资产后重新上传..."
  gh release delete "v$NEW_VERSION" --repo "$REPO" --yes >/dev/null
  gh release create "v$NEW_VERSION" "$APK_PATH" --repo "$REPO" \
    --title "A股量化机器人 v$NEW_VERSION" --notes "$RELEASE_NOTES"
else
  gh release create "v$NEW_VERSION" "$APK_PATH" --repo "$REPO" \
    --title "A股量化机器人 v$NEW_VERSION" --notes "$RELEASE_NOTES"
fi

# ---------- 4. 同步更新 latest.json（应用内检测更新清单）----------
LATEST_JSON="$ROOT_DIR/latest.json"
if [ -f "$LATEST_JSON" ]; then
  echo "[4/4] 同步 latest.json -> v$NEW_VERSION (versionCode=$NEW_VERSION_CODE)"
  # 更新 JSON 里的 version / versionCode，并把最新版本信息追加到 changelog 前部
  python3 - "$LATEST_JSON" "$NEW_VERSION" "$NEW_VERSION_CODE" "$RELEASE_NOTES" <<'PY'
import json, sys, os
path, ver, code, notes = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
with open(path, encoding="utf-8") as f:
    data = json.load(f)
latest = data.get("latest", {})
latest["version"] = ver
latest["versionCode"] = code
old = latest.get("changelog", "")
latest["changelog"] = (notes + ("" if old == "" else "\n" + old))
data["latest"] = latest
with open(path, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
    f.write("\n")
print("已更新", ver, code)
PY
  # 把 latest.json 提交并推送到 main，确保应用内能读到最新版本清单。
  # 此前脚本只在本地改文件、从不推送，导致远端 latest.json 长期停留在旧版本。
  echo "同步 latest.json 到远端 main ..."
  git add "$LATEST_JSON"
  if git commit -m "chore: 同步latest.json至v$NEW_VERSION" >/dev/null 2>&1; then
    git push origin HEAD:main || echo "⚠️  git push 失败，请手动推送 latest.json"
  else
    echo "latest.json 无变更，跳过提交"
  fi
fi

# ---------- 5. 输出永久直链 ----------
echo
echo "========== ✅ 发布完成 =========="
echo "  Release : https://github.com/$REPO/releases/tag/v$NEW_VERSION"
echo "  永久直链(写入 AppStore DEFAULT_UPDATE_URL)："
echo "  https://github.com/$REPO/releases/latest/download/$APK_ASSET_NAME"
echo "=================================="