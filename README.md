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

Release 包使用 `keystore.properties` 里的签名：

```bash
./gradlew :app:assembleRelease
```

APK 在 `app/build/outputs/apk/release/app-release.apk`。

`xposed-api` 只在编译时提供 Xposed 接口，不会打进 APK。

## 自动编译

推送到 `main` 后，Actions 跑单元测试，编出已签名的 release APK，打 tag，并发布到 GitHub Releases。

tag 形如 `v1.0.0.12`。`1.0.0` 是 `app/build.gradle.kts` 里的 `releaseVersion`，`12` 是这次 Actions 的运行编号。这个编号写入 APK 的 `versionCode`，后一次发布可以直接覆盖安装。安装包文件名是 `heshang-v1.0.0.12.apk`。

签名文件是 `keystore/heshang-release.p12`，密码在 `keystore.properties`。每次发布的证书相同，可以直接覆盖安装。

Java 源码包名仍是 `com.minedie.keepalive`。这和安装后的应用 ID 不是同一个字符串。
