# GkdAiExplorer - 本地编译验证指南

## 1. 环境安装（仅首次）

| 组件 | 推荐路径 | 备注 |
| ------ | ---------- | ------ |
| JDK 17 | `F:\java\jdk-17` | Temurin / Microsoft / Oracle 均可 |
| Android SDK | `F:\AndroidSdk` | 用 Android Studio 安装，或 `cmdline-tools` |
| Android Studio | `F:\AndroidStudio` | 勾选 SDK Platform 34、Build Tools 34.0.0、Emulator |

**环境变量（用户或系统 PATH 追加）：**

```
JAVA_HOME=F:\java\jdk-17
ANDROID_HOME=F:\AndroidSdk
PATH=%PATH%;%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\cmdline-tools\latest\bin
```

验证：

```powershell
java -version          # 17.x
adb version            # Android Debug Bridge
```

---

## 2. 打开项目 & 首次编译

```powershell
cd C:\Users\Administrator\GkdAiExplorer
.\gradlew.bat --version        # 验证 Wrapper 能跑
.\gradlew.bat assembleDebug    # 完整编译，产出 app/build/outputs/apk/debug/app-debug.apk
```

> 首次会下载 Gradle 8.5 + 依赖，**需联网**，预计 3-8 分钟。

---

## 3. 常见报错 & 对策

| 报错 | 解决 |
| ------ | ------ |
| `SDK location not found` | 检查 `local.properties` 里的 `sdk.dir=F:/AndroidSdk` 路径是否存在 |
| `Could not find com.android.tools.build:gradle:8.x` | 网络问题，配置阿里/腾讯 Maven 镜像（见下） |
| `JDK 17 required` | 确认 `JAVA_HOME` 指向 JDK 17，且 `java -version` 输出 17.x |
| `Kotlin daemon crashed` | `gradle.properties` 已加大内存，若仍崩把 `-Xmx4g` 改 `-Xmx6g` |
| `Duplicate class` / `Manifest merger failed` | 清理缓存：`.\gradlew.bat clean` 后重编 |

---

## 4. 加速依赖下载（可选）

在 `settings.gradle.kts` 顶部加入：

```kotlin
pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
        google()
    }
}
```

---

## 5. 验证安装成功

```powershell
.\gradlew.bat installDebug   # 需连真机/模拟器（已开 USB 调试）
adb shell am start -n com.toolbox.smartcleaner/.ui.MainActivity
```

看到 **“极简工具”** 启动页、底部四个 Tab（首页/发现/规则/设置）即为成功。

---

## 6. 报错反馈模板

编译失败时，复制以下内容发给我：

```
> Task :app:compileDebugKotlin FAILED
e: C:\Users\Administrator\GkdAiExplorer\app\src\main\java\...\xxx.kt: (12, 5): Unresolved reference: yyy
```

我会直接给出修复的文件内容。
