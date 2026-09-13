# 余额小鲸鱼 · DeepSeek 余额桌宠（Android 版）

一只浮在手机桌面上的蓝色小鲸鱼，帮你盯着 DeepSeek API 余额。

> **原作 / Original work**：[MeteorNOX/DeepSeek-Balance-Whale-Widget](https://github.com/MeteorNOX/DeepSeek-Balance-Whale-Widget)（MIT）
> 小鲸鱼形象、气泡样式、拖拽吸附与左吸附镜像的交互设计均来自上游，本项目只是 Android 移植；立绘取自上游 `assets/DSniang1.png`。

## 功能

- 桌面悬浮，退出应用也不消失；点一下刷新余额并弹出气泡，再点收起
- 点气泡换台词（顺序轮播、一轮内不重复）；快速连点 5 下会「生气」
- 长按 → 悬浮菜单：大小 / 峰谷文案 / 气泡 / 音效

## 安装

1. 下载 [Releases](../../releases) 里的 APK，装到 Android 8.0（API 26）以上
2. 打开应用 → 设置 → 填 DeepSeek API Key（`sk-...`，只存在本机）→ 保存
3. 回主页点「一键获取所需权限」，按提示授予后点「启动小鲸鱼」

## 构建

```sh
ANDROID_JAR=/path/to/android-34/android.jar R8_JAR=/path/to/r8.jar bash tools/build_apk.sh
# 产物：build/whale-pet.apk
```

也可用 Android Studio（AGP 8.x）直接打开工程。

## 说明

- 包名 `com.deepseek.whalepet`，纯 Java，无第三方依赖
- 余额接口：`GET https://api.deepseek.com/user/balance`
- 「透明区域可点穿」依赖隐藏 API（`ViewTreeObserver.InternalInsetsInfo`），设备需执行一次：
  `adb shell settings put global hidden_api_policy 1`（不执行则退回矩形窗口，功能不受影响）

## 致谢 / License

原始设计与立绘来自 [MeteorNOX/DeepSeek-Balance-Whale-Widget](https://github.com/MeteorNOX/DeepSeek-Balance-Whale-Widget)（MIT）。

本项目以 **MIT** 许可发布，详见 [LICENSE](LICENSE)。
