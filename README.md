# KotlinMCUI Web 后端

把 KotlinMCUI 的界面画在浏览器里。框架跑在 JVM 上，浏览器只做画布——它不依赖 Minecraft，只依赖
核心库。

设计思路、线上格式与实测数据见 [`docs/dev/web-backend.md`](docs/dev/web-backend.md)；用户侧怎么用
写在核心库的 `docs/`，这里不重复。

## 运行

```powershell
# 起 HTTP + WebSocket 服务，用浏览器打开它打印的地址
.\gradlew.bat serve -Ppage=entry -Pport=8123

# 拍照验收：等一个浏览器连上来，请它把画布拍成 PNG，写盘后退出
.\gradlew.bat serve -Ppage=entry -Pport=8123 -Pcapture=capture.png

# 只录制帧并报告线上开销（不需要浏览器，也不需要端口）
.\gradlew.bat measure -Ppage=color -Pframes=30
```

端口默认 8080，容易和别的服务撞，所以示例一律用 `-Pport`。页面名见 `Pages.kt` 的 `PAGE_NAMES`。
`measure` 的输出全在 stdout。`-PgameDir=<Minecraft 安装目录>` 让物品与方块贴图从
`versions/*/*.jar` 与 `mods/*.jar` 里取；不给就是占位框。

## 构建

核心库从**同级检出**取：`../kotlinmcui/build/libs/kotlinmcui-<version>.jar` 存在就用它，否则回落到
jitpack 上的同名版本（版本号是 `gradle.properties` 里的 `kotlinmcui_version`）。

**那个回落是静默的，而且两份 jar 不是同一份东西**：本地 Gradle 缓存里那份 jitpack 版本是 919813 B，
而同级的兄弟 jar 是本地构建出来的**另一个文件**（本次 1.13 MB）——**差约 200 KB，却是同名同版本号**。
回落时不会报错、离线也照样解析得出来，于是你会对着**另一份核心库**调试：量出来的数字、看到的报错
都可能对不上，而且没有任何提示指向这个原因。

所以**每次编译或测量之前，先确认兄弟 jar 在位**：

```powershell
Test-Path ..\kotlinmcui\build\libs\kotlinmcui-1.0.0-alpha.32.jar
```

不在就先在同级核心仓把它构建出来，再回到这里。

## alpha 的边界：一个客户端

这个后端**按单客户端设计**。多连接能工作，但不是被支持的能力，而且后果是可观察的：

- **视口是共享的**，两条尺寸不同的连接会互相把对方的基帧作废，**帧复用彻底失效**——静止页面从
  1 字节退回整帧；
- **语言是共享的**，第一个连上来的客户端决定所有人的界面语言；
- **输入没有归属**，两个鼠标都能操作同一个页面栈。

宿主若要服务多个客户端，需要自己做尺寸、语言与输入的仲裁。
