# Gem桌面 · 仿 macOS 风格的 Android TV 启动器

一个**纯手写、零依赖**的 Android 桌面（Launcher），给电视遥控器用：没有 Gradle、没有 AndroidX、没有第三方库，全部界面用一块 `Canvas` 自绘，构建链只有 `aapt2 → javac → d8 → zipalign → apksigner`。

- 目标设备：Android TV / 电视盒子（Android 9 实测开发），横屏 1920×1080
- minSdk 23 / targetSdk 28 / compileSdk 33
- 包名 `com.gemjiang.launcher`，安装后可在系统设置里设为默认桌面

## 特色

**虚拟圆环光标（不是系统焦点）**
方向键推动一支自己画的圆环光标，带加速、惯性、松手磁吸；光标指到哪儿焦点就“汇合”到哪儿。支持三种速度档（慢 / 中 / 快），也支持空鼠 / 手柄（`SOURCE_MOUSE` / `SOURCE_JOYSTICK`）。
按键在 `Activity.dispatchKeyEvent()` 直接抢过来转发给自绘视图，不走系统焦点链路，避免遥控器长按被系统吃掉。

**窗口系统**
自绘窗口，可拖动（按住标题栏 + 方向键）、可置顶、红绿灯关闭；窗口整块命中，点空白只置顶，不会穿透点到后面的图标。

**灵动岛**
屏幕顶部居中的黑胶囊，点一下展开：

| 区域 | 功能 |
| --- | --- |
| 左 | 时间 / 秒 / 日期，每秒刷新 |
| 左 | 音频状态：系统有声音在放就跳波形 |
| 右 | 音量减 / 进度条 / 音量加，直接调电视媒体音量 |
| 右 | 5 分钟计时器，收起后仍在胶囊里倒计时，归零自动弹出提醒 |
| 全局 | 打开任意 App 时弹出「正在打开 ×××」+ 应用图标，随后自动收起 |

岛只存在于启动器自己的画布上，进入别的 App 就消失。

**桌面与 Dock**
6×4 图标网格分页、7×5 启动台、放大版 Dock（悬停放大）、菜单栏 8 个下拉菜单接到真实动作、深色液态玻璃小部件（点开是时钟 / 日历窗口）。

![桌面](docs/desktop.png)
![灵动岛](docs/dynamic-island.png)
![时钟窗口](docs/clock-window.png)
![日历窗口](docs/calendar-window.png)

## 目录结构

```
.
├── AndroidManifest.xml
├── build.sh                     # 一把梭构建脚本（无需 Gradle）
├── res/
│   ├── drawable-nodpi/ic_launcher.png
│   └── values/{strings,styles}.xml
├── src/com/gemjiang/launcher/
│   ├── LauncherActivity.java     # 入口，抢按键、转发给视图
│   ├── DesktopView.java          # 唯一状态机 + 全部绘制（约 2500 行）
│   ├── Painter.java              # 绘图原语：圆角、玻璃、图标 squircle 蒙版、字形
│   ├── Apps.java                # 已安装应用扫描 + 图标异步解码
│   └── Wallpaper.java            # 壁纸绘制
└── docs/                         # 截图
```

所有几何都按 **1920×1080 设计坐标系** 写死，绘制时整体 `canvas.scale(min(w/1920, h/1080))` 等比缩放居中，其它分辨率会留黑边但不变形。

## 构建

需要：Android SDK（`build-tools` 含 aapt2 / d8 / zipalign / apksigner，`platforms;android-33` 的 `android.jar`）、JDK 17、`zip`。

```bash
export ANDROID_SDK=~/android-sdk     # 或 ANDROID_HOME
export JDK_HOME=~/jdk-17             # 可选，用 PATH 里的 javac 也行
./build.sh
# => GemDesktop_1.5.0.apk
```

脚本会自动从 `AndroidManifest.xml` 读版本号；首次构建会在项目根目录生成一次性 `debug.keystore`（口令 `android`，仅用于调试安装，不要用于正式发布）。

## 安装

```bash
adb install -r GemDesktop_1.5.0.apk
# 设为默认桌面（部分电视需要在系统设置里手动选）
adb shell cmd package set-home-activity com.gemjiang.launcher/.LauncherActivity
```

也可以用 U 盘 / 电视上的文件管理器直接装 APK（需要允许“未知来源”）。

## 已知限制

- **第三方 App 真窗口化**：普通 APK 无法强制别的 App 以自由窗口启动，代码里已用反射调 `setLaunchWindowingMode(5)` + `setLaunchBounds` 尽力尝试，失败会退回全屏并提示。要真正窗口化需要 ROM 打开 freeform：
  ```bash
  adb shell settings put global enable_freeform_support 1
  adb shell settings put global force_resizable_activities 1
  ```
- **灵动岛的媒体信息**：只能通过 `AudioManager.isMusicActive()` 判断“有没有声音”，拿不到歌名 / 封面 / 进度——那需要 `NotificationListenerService` 授权，普通 APK 在电视上拿不到。
- 睡眠 / 重启 / 关机需要系统权限（`DEVICE_POWER`），当前灰掉不可点。

## License

MIT，见 [LICENSE](LICENSE)。
