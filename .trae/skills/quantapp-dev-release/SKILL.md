---
name: "quantapp-dev-release"
description: "QuantApp Android 开发与 GitHub Release 发布全套套路。Invoke when user asks to 改 Android 代码、build APK、发 GitHub release、或同步 latest.json。"
---

# QuantApp 开发 & 发布套路

这是 QuantApp（A股量化机器人，Android Kotlin 项目）专用的开发、构建、发布工作流模板。涵盖了本仓库历次迭代中反复复用的命令、约定和踩过的坑。

## 1. 环境前置

```bash
# 必须用 JDK 17（Android Gradle Plugin 不兼容 Java 21/25）
export JAVA_HOME="/root/.local/share/mise/installs/java/17.0.2"
# 本仓库没有 gradlew wrapper，用系统级 gradle
which gradle
```

## 2. 版本号规则

- 位置：`app/build.gradle.kts` → `defaultConfig`
- `versionCode`：每次发布 +1（整数，API 用）
- `versionName`：语义化小版本迭代，如 `2.23.0` → `2.24.0`
- 提版本号必须和发布绑定，不要单独提交

## 3. GitHub Release 认证

**不要把 token 存进仓库。** 从 remote URL 动态提取：

```bash
TOKEN=$(git remote get-url origin | sed -E 's#.*x-access-token:([^@]+)@.*#\1#')
export GH_TOKEN="$TOKEN"
gh auth status
```

历史上 `.release-token` 文件曾被误提交导致推送被 GitHub 拦截，务必保持 `.gitignore` 含 `.release-token`。

## 4. 构建命令

```bash
# Debug（改动后快速验证，~1min）
gradle assembleDebug -Dorg.gradle.java.home="$JAVA_HOME" --console=plain

# Release（最终产物，先 clean 再 build，~3min）
gradle clean assembleRelease -Dorg.gradle.java.home="$JAVA_HOME" --console=plain

# 产物路径
ls app/build/outputs/apk/release/app-release.apk
```

## 5. 发布流程（脚本化）

一次性发布命令骨架，按需改 TAG / NOTES / VERSION：

```bash
cd /workspace/QuantApp

# 1. 改版本号
# 手动编辑 app/build.gradle.kts: versionCode+1, versionName

# 2. 提交代码
git add -A && git commit -m "feat: <本次改动摘要>" && git push origin HEAD:main

# 3. 准备产物
TOKEN=$(git remote get-url origin | sed -E 's#.*x-access-token:([^@]+)@.*#\1#')
export GH_TOKEN="$TOKEN"
VERSION="v2.24.0"
mkdir -p build/releases && cp app/build/outputs/apk/release/app-release.apk "build/releases/aquant-release.apk"

# 4. 创建 Release（同名 tag 存在则先删）
NOTES="## $VERSION
- <迭代项 1>
- <迭代项 2>"
gh release delete "$VERSION" --repo tangtangchen23/aquant --yes 2>/dev/null
gh release create "$VERSION" "build/releases/aquant-release.apk" \
  --repo tangtangchen23/aquant \
  --title "A股量化机器人 $VERSION" \
  --notes "$NOTES"

# 5. 同步 latest.json（覆盖，不要追加）
# 编辑 latest.json: version / versionCode / changelog
git add latest.json && git commit -m "chore: 同步latest.json至$VERSION" && git push origin HEAD:main
```

### 关键约束

- **latest.json 的 changelog 必须覆盖而非追加**。历史脚本曾因追加导致日志累积所有版本内容。
- **APK 直链统一用** `https://github.com/tangtangchen23/aquant/releases/latest/download/aquant-release.apk`，不要绑版本号路径，方便热更新。
- Release 创建后用 `gh release list --repo tangtangchen23/aquant --limit 3` 确认 TAG 已被标记为 Latest。

## 6. UI 设计约定（可复用资源）

设置页和二级弹窗已统一美化风格，新弹窗直接复用：

| 资源 | 用途 |
|------|------|
| `bg_option.xml` | 分段选择卡背景（RadioButton 用） |
| `bg_card.xml` | 圆角卡片背景（列表容器用） |
| `bg_dialog.xml` | 弹窗整体背景 |
| `bg_row.xml` / `bg_row_icon.xml` | 设置页列表行 / 左侧图标圆底 |
| `chip_*.xml`（chip_trade / chip_tband / chip_risk / chip_alert / chip_ai / chip_live / chip_theme / chip_update） | 分类彩色徽标 |
| `colors.xml` 中的 `set_trade / set_tband / set_risk / set_alert / set_ai / set_live / set_theme / set_update` 及对应 `set_tint_*` | 各分类主题色 |
| `ic_close.xml` / `ic_settings.xml` / `ic_fullscreen.xml` | 通用图标 |
| `?attr/indicatorActiveText` / `?attr/onBrand` | 主题属性，自动适配浅色/深色/红色主题 |

### 弹窗布局模板参考

`dialog_settings_trade.xml` 是标杆模板：头部分类徽标 + 标题 + 关闭按钮 → 主操作区（分段选卡）→ 可选项（Switch/输入框）。新建弹窗直接从它复制改。

### 弹窗打开模板（Kotlin）

```kotlin
private fun showXxxDialog() {
    val ctx = requireContext()
    val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_xxx, null)
    // ... 绑定控件、设初始值、监听 ...
    AlertDialog.Builder(ctx).setView(v).create().apply {
        window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
        show()
    }
}
```

## 7. 常见陷阱清单

| 陷阱 | 症状 | 解法 |
|------|------|------|
| JDK 版本不对 | AGP 报 Kotlin 兼容性错误 | 强制 `JAVA_HOME=.../java/17.0.2` |
| latest.json changelog 追加 | 更新弹窗显示所有历史版本内容 | 每次发布覆盖整个 changelog 字段 |
| token 提交到 git | GitHub 安全扫描拒绝推送 | `.gitignore` 加 `.release-token`，token 从 remote URL 临时提取 |
| 手动买卖价格只读 | InputType.TYPE_NULL + 不可聚焦 | 改为 `TYPE_CLASS_NUMBER or TYPE_NUMBER_FLAG_DECIMAL` + 可聚焦 |
| 更新弹窗按钮颜色看不清 | 硬编码颜色在深色主题下不可见 | 用 `themeAttrColor(ctx, R.attr.onBrand)` |
| 回滚到旧标签构建失败 | 旧 commit 缺模块（如 PendingOrder） | 不要 `git reset --hard`，用 `git checkout <hash> -- <file>` 选择性回滚 |
| 完整回滚覆盖新功能 | 挂单、实时价等被抹掉 | 选择性 restore 目标文件，保留主分支新增模块 |

## 8. 项目速览

```
/workspace/QuantApp/
├── app/build.gradle.kts          # 版本号在这里
├── app/src/main/java/com/quantapp/trader/ui/
│   ├── SettingsFragment.kt       # 设置页（二级弹窗入口）
│   ├── ChartFragment.kt          # K线页（含主图快捷设置）
│   └── StrategyFragment.kt       # 策略页
├── app/src/main/res/
│   ├── layout/fragment_settings.xml
│   ├── layout/fragment_chart.xml
│   ├── layout/dialog_settings_*.xml   # 设置二级弹窗
│   ├── layout/dialog_chart_settings.xml
│   ├── values/colors.xml
│   └── drawable/chip_*.xml, bg_*.xml  # UI 美化资源
├── latest.json                   # 热更新 manifest，发布必改
└── scripts/release.sh            # 旧发布脚本（参考用，实际用上面的骨架）
```
