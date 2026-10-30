# 红果自动跳过 v2.0（修复版）

通过无障碍服务检测「红果免费短剧」「红果免费漫剧」的广告是否播放完毕，然后自动上滑继续播放，无需 Root。

- 包名：`com.hongguo.auto`（与原版一致，可直接覆盖安装；签名不同，需先卸载旧版）
- 目标应用：`com.phoenix.read`（红果免费短剧）、`com.kylin.read`（红果免费漫剧）

## 下载

- 直接安装：[dist/hongguo-auto-skip-v2.0.apk](dist/hongguo-auto-skip-v2.0.apk)（release，38 KB）
- 自己编译：`./gradlew :app:assembleRelease`。仓库自带调试签名 `keystore/debug.keystore`（密码 `android`，别名 `androiddebugkey`），
  只为让 clone 下来就能构建，**对外分发请换成你自己的签名**。

---

## 一、旧版为什么"平板能用、手机不行"，以及"漫剧能用、短剧不行"

旧版（v1.0）反编译后可以看到 3 个类：`MainActivity`、`HongguoAccessibilityService`、`AppLog`。问题都集中在检测链路：

| # | 旧版写法 | 后果 |
|---|---|---|
| 1 | 只在 `onAccessibilityEvent()` 回调里检测 | 手机 ROM（MagicOS / 鸿蒙）会合并、丢弃 `TYPE_WINDOW_CONTENT_CHANGED` 事件，一旦事件不来就**永远不检测**；平板系统事件派发更"宽"，所以能跑 |
| 2 | 在无障碍回调（主线程）里递归遍历整棵树，根节点为空时还 `Thread.sleep(200)` | 阻塞主线程：掉事件、手势派发失败、重则 ANR。手机刷新率和事件量都比平板大，症状被放大 |
| 3 | 只读 `getRootInActiveWindow()` 一个窗口 | 广告以独立窗口 / 悬浮窗 / 广告 SDK 窗口展示时，根节点不是红果的树 → 什么都看不到 |
| 4 | 检测规则只有两条：①文本"第N集" ②控件 `id=jer` 且文本为空 | `id=jer` 是**漫剧**的集数控件，**短剧**的广告页（广告角标 + 倒计时 + 跳过按钮）完全匹配不到，所以短剧永远不上滑 |
| 5 | `AD_KEYWORDS` 全是"立即下载/去游戏"这类落地页按钮 | 广告**播放中**的界面通常没有这些词，检测不到 |
| 6 | 清单里 `<service android:exported="false">` | 无障碍服务由 system_server 绑定，非本进程。新系统/部分 ROM 上会更严格地拒绝绑定 → 服务看起来"已授权"却连不上 |
| 7 | 参数默认值前后不一致：`check_interval` 默认取 `DEFAULT_COOLDOWN`；服务端 `confirm` 默认 2、界面默认 1；`debug_mode` 服务端 false、界面 true | 界面显示和实际行为不一致，用户以为按 1 次确认就滑，其实要 2 次 |
| 8 | 调试模式每收到一个事件就 dump 整棵节点树 | 手机上等于把主线程打死 |
| 9 | 声明了 `POST_NOTIFICATIONS` 但从不申请 | Android 13+ 通知静默丢弃，"已自动跳过广告"提示看不到 |

另外：旧版滑动坐标用的是**整屏** `heightPixels`（含状态栏/手势区），且判定逻辑是"看到广告特征就上滑"，
既没实现"检测广告播放完毕"，也容易在广告还在播时误触。

## 二、v2.0 的修复

1. **轮询为主、事件为辅**：检测跑在独立 `HandlerThread` 上，约 400ms 轮询一次；无障碍事件只用于"尽快再扫一次"，不再决定节奏。ROM 丢事件也不影响功能。
2. **多窗口扫描**：`getWindows()` 收集所有窗口（活动窗口 + 悬浮窗 + 广告 SDK 窗口），合并成一次检测；广告在独立窗口里也能识别。
3. **主线程零负担**：节点遍历全部在工作线程；删掉 `Thread.sleep`；日志只记录状态变化。
4. **两个 App 都支持**：
   - 通用规则：广告角标（"广告"/"AD"）、跳过按钮、倒计时（`5s`/`5秒`/`剩余3s`）、落地页词、`id` 里含 `ad/countdown/skip`；
   - "上滑继续观看"这类提示 = 广告已播完；
   - 漫剧额外保留旧版验证过的 `id=jer` 空文本特征（不破坏平板上的既有行为）；
   - 可在界面分别开关两个 App。
5. **真正的"广告播完"判定**（状态机 `AdStateMachine`）：
   - 倒计时还在走 → 不动；
   - 倒计时停住 / 广告角标持续 N 毫秒不变 → 认为播完，上滑；
   - 广告特征整体消失 → 上滑；
   - 出现"上滑继续观看" → 立刻上滑；
   - 冷却 2.5s、单段广告最多 3 次、上滑后 4s 静默，避免连环误滑。
6. **滑动坐标更稳**：优先用目标窗口的 bounds，退回整屏；起点/终点按百分比，避开状态栏。
7. **清单修正**：无障碍服务 `android:exported="true"`，运行时再刷一遍 `serviceInfo`（视图 id、交互窗口、事件类型），并加了 `settingsActivity`。
8. **Android 13+ 通知权限**运行时申请；通知渠道降为 LOW，不再打扰。
9. **参数体系统一**：所有默认可视化、可调，`Prefs` 里集中管理并做范围钳制；旧的 prefs 键名保留，升级不丢设置。
10. **诊断能力**：界面上「抓取当前页面节点」会把当前所有窗口的控件树（包名 / id / text / desc / bounds）写进日志并落盘；「导出」生成 txt。手机出问题可直接抓一份发出来，不必再猜。

## 三、代码结构

```
app/src/main/java/com/hongguo/auto/
├── MainActivity.java                 界面：开关、参数、诊断、日志
├── HongguoAccessibilityService.java  无障碍服务：轮询、多窗口扫描、手势
├── NodeScanner.java                  AccessibilityNodeInfo → UiNode + 窗口快照/导出
├── Prefs.java                        参数读写（兼容旧键名）
├── ServiceState.java                 服务↔界面共享状态
├── AppLog.java                       环形日志
└── core/                             纯 Java，无 Android 依赖，可在电脑上跑单测
    ├── UiNode.java                   节点快照
    ├── AdSignals.java                一次扫描的特征
    ├── AdDetector.java               规则识别
    ├── AdStateMachine.java           什么时候上滑
    └── DetectorConfig.java           参数
app/src/test/java/com/hongguo/auto/    24 个单元测试（检测规则 + 状态机）
```

## 四、编译

```powershell
# JDK 17 + Android SDK 34
$env:JAVA_HOME = '<jdk-17>'
$env:ANDROID_HOME = '<android-sdk>'   # 或写进 local.properties 的 sdk.dir
gradle :app:testDebugUnitTest         # 24 个单测
gradle :app:assembleDebug             # 产物 app/build/outputs/apk/debug/app-debug.apk
```

用 Android Studio 直接打开本目录也可以（AGP 8.5.2 / Gradle 8.7 / compileSdk 34 / minSdk 24 / targetSdk 34）。

## 五、手机上看不到效果时这样排查

1. 确认「设置 → 无障碍 → 红果广告自动跳过服务」已开启，且应用界面顶部显示「无障碍已连接 ✓」。
   荣耀/OPPO 等系统上，侧载应用的无障碍开关可能是灰的：点应用里「打开本应用设置」，右上角 ⋮ → **允许受限设置**，再回去开。
2. 荣耀 MagicOS / 华为鸿蒙额外要做：
   - 应用里点「电池优化设为不限制」，或手动：电池 → 应用耗电管理 → 不限制 / 允许后台活动；
   - 应用管理 → 红果自动跳过 → 允许「自启动」「关联启动」；
   - 最近任务里给本应用加锁。
   否则系统会在锁屏/后台一段时间后把无障碍服务清掉（这也是"平板正常、手机不行"的常见原因之一）。
3. 打开红果，等广告出现时点应用里的「抓取当前页面节点」，再点「导出」，把 txt 发出来。
   有了真实节点树就能精确加规则（比如短剧的广告倒计时控件 id）。

## 六、验证情况

- `gradle :app:testDebugUnitTest`：**26 个单元测试全部通过**（检测规则 17 + 状态机 9），覆盖漫剧空集数卡片、
  短剧广告角标+倒计时+跳过按钮、正常播放页面、隐藏节点、多窗口、自定义关键词、冷却/次数限制等。
- `gradle :app:assembleDebug/Release`：构建成功；`aapt2 dump badging` 确认包名/权限/无障碍组件；
  `apksigner verify` 通过。
- **没有在荣耀 MagicOS 真机上验证过**（这台电脑上没有连接设备；本机 Android 模拟器在 WHPX 下启动失败）。
  所以下面这份"真机排查"请务必看一下，出问题时把抓取的节点树发出来。

## 七、已知限制

- 只在电脑侧做了规则级单元测试和构建验证，**没有在荣耀 MagicOS 真机上跑过**（当前环境没有连接设备）。
- 如果某个版本的短剧广告页既没有"广告"角标、也没有倒计时/跳过按钮（纯画面），无障碍层面无法识别，需要用「抓取当前页面节点」拿到实际控件再加规则。
- 广告跳转到外部 App（电商/游戏）时，前台包名不再是红果，本应用不会跟随跳转操作；等回到红果后继续检测。
- 覆盖安装需要先卸载旧版（签名不同）。

## 八、测试用「假红果短剧」

`E:\DSH\mockdrama` 是一个只用于测试的小 App：包名故意写成 `com.phoenix.read`，界面是一个广告页
（右上角"广告"、中间 5s 倒计时、右下"跳过广告"、左下"立即下载"），并在左上角显示"上滑次数: N"。

在模拟器/备用机上：

```powershell
adb install -r .\mockdrama\app\build\outputs\apk\debug\app-debug.apk   # 假红果
adb install -r .\红果自动跳过-v2.0-debug.apk                          # 本应用
adb shell settings put secure enabled_accessibility_services com.hongguo.auto/com.hongguo.auto.HongguoAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell am start -n com.phoenix.read/.MockActivity
adb logcat -s Service MockDrama
```

倒计时走到 0、广告标识停住 1.2 秒后，日志里应出现 `上滑: pkg=com.phoenix.read ...`，界面上的"上滑次数"变为 1。

## 九、这台机器上的工具目录（可删除）

调试时下载的工具都在工作区里，**都可以删**（不影响源码和 APK）：

- `E:\DSH\tools`（JDK 17 / Gradle 8.7 / jadx / 安装包，约 1.5 GB）
- `E:\DSH\sdk`（Android SDK 34 + build-tools 34 + 模拟器，约 4.5 GB）
- `E:\DSH\gradle-home`（Gradle 缓存，约 320 MB）
- `E:\DSH\work`（反编译出来的旧版源码，用来对照）

删除后若要重新构建，装 Android Studio 或用 `gradle :app:assembleRelease` 即可，项目本身是标准结构。
