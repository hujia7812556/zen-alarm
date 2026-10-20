# Repository Guidelines

## 项目结构与模块职责

本项目是 Android 个人应用“超禅定时”，包名为 `com.hujia.zenalarm`，最低 API 31，目标 API 36。

- `src/com/hujia/zenalarm/`：Java 源码。`MainActivity` 管理界面；`ZenScheduler`、`AlarmReceiver` 负责预约和触发；`ZenGuardService` 管理前台守护与独立通知；`ScheduleStore` 保存定时；`AlarmTime`、`FixedTime`、`DurationText` 提供时间、周额度计算与解析；`ZenDurationReader` 读取可见时长。
- `res/`：图标、主题、字符串及无障碍服务配置；`AndroidManifest.xml` 声明组件和权限。
- `tests/AlarmTimeTest.java`、`tests/FixedTimeTest.java`：可独立运行的 JVM 测试。`README.md` 说明使用方式，`verification*.txt` 记录各版本验证范围。
- `build/`、`dist/`、`.signing/` 分别存放构建缓存、APK 和本机密钥，均被忽略。SDK 属于外部依赖，不将 SDK 或反编译材料纳入源码。

## 构建与开发命令

以下命令在本文件所在目录执行。需要 Python 3、JDK、Android API 36 平台包和 Build Tools 36.0.0；项目不使用 Gradle。

```sh
python3 build.py
adb devices
adb -s <serial> install -r dist/zen-alarm-v0.5.2.apk
adb -s <serial> shell am start -n com.hujia.zenalarm/.MainActivity
```

构建命令依次运行测试、编译资源和 Java、生成 DEX、对齐并签名 APK，最后验证签名并输出 SHA-256。安装命令中的版本号应与实际产物一致。

默认构建目录为 `build/`，APK 输出到 `dist/`。SDK 默认读取 `ANDROID_HOME`、`ANDROID_SDK_ROOT` 或 macOS 标准目录；特殊布局使用被忽略的 `build.local.json`。`ZEN_ANDROID_JAR`、`ZEN_BUILD_TOOLS`、`ZEN_BUILD_DIR` 和 `ZEN_KEYSTORE` 优先覆盖配置，详见 README。

## 编码风格与命名

Java、Python 使用四空格缩进；Java 保持 `--release 8` 兼容。类名使用 `PascalCase`，方法和变量使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。新增函数添加中文功能注释，关键边界逻辑补充中文说明。保持现有文件风格；当前未配置格式化或 lint 工具。

## 测试与验证

测试通过 `main()` 和 `AssertionError` 实现，不依赖 JUnit。`python3 build.py` 必须通过 6 项时间边界与 20 项固定定时检查，覆盖跨日、跨年、夏令时、周一额度重置、同日叠加拒绝和重载后额度保留；当前没有覆盖率门槛。

新增测试命名为 `*Test.java`，并在 `build.py` 中加入编译和执行步骤。时间计算修改应补充确定性用例；界面修改做针对性的真机检查，记录实际验证结果，不能将构建成功等同于锁屏或息屏验证通过。启动测试会实际进入超禅，应使用短时长。

## 提交与 Pull Request

提交信息使用中文 subject 和详细 description，例如“优化时长刷新后的返回行为”，正文说明原因、行为变化及验证结果。未经用户明确要求，不执行提交或推送。

PR 应说明问题、变更范围和验证方式；有关联 issue 时附链接，界面变更附截图。版本更新同步检查 `AndroidManifest.xml` 版本号、界面版本文案和 README，并更新对应验证记录；APK 文件名自动读取清单版本。

## 权限与配置边界

固定每日 01:00 必须在存储和调度层保留，不能只隐藏界面开关。推迟按执行日期所在周计数，每周最多 2 次，每次至 01:30，同日不得叠加；修改该规则应补充测试。

保留无网络、无 Root、无 Shizuku 的运行方式。时长读取仅针对原生超禅卡片，不读取其他应用或私有数据库，不修改原生时长；自动读取应与用户主动打开页面的行为区分。

签名密钥默认位于 `.signing/personal.jks`。保留同一密钥用于覆盖升级，不将密钥、设备截图或原始诊断数据提交到仓库；执行暂存前检查 `git status --ignored`。
