# 和尚

ColorOS 上的 LSPosed 保活模块。安装后的应用 ID 是 `com.hakuzero.heshang`。

巡检跑在系统进程里。给一个应用选定服务之后，进程掉线会被静默拉起，并抬高存活优先级。勾选过的无障碍服务被清掉时，会写回去。

划掉「和尚」的界面不会停掉巡检。在系统设置里对它点强行停止之后，需要再打开一次，巡检才能继续读到配置。

## 启用

1. 安装 APK。
2. 在 LSPosed 里启用本模块，作用域勾选系统框架和「和尚」。
3. 重启手机。
4. 打开总开关。在「守护」里勾选应用，并选中要拉起的那个服务。

同一个应用不要同时交给不死鸟。本模块不钩 `com.oplus.athena` 和 `com.oplus.battery`。

最低系统是 Android 10。改过作用域或换过应用 ID 之后，要再重启一次，新代码才会进系统进程。

## 本地编译

需要 JDK 17 和 Android SDK 36。

```bash
./gradlew :core:test :app:assembleDebug
```

Debug 包在 `app/build/outputs/apk/debug/app-debug.apk`。

`xposed-api` 只在编译时提供 Xposed 接口，不会打进 APK。

## 自动编译

推送到 GitHub 后，Actions 会跑单元测试并编出 debug APK。构建产物的名字是 `heshang-debug`。

Java 源码包名仍是 `com.minedie.keepalive`。这和安装后的应用 ID 不是同一个字符串。
