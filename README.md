# 语音球（Voiceball）

一个很轻的 Android 悬浮球：**按住说话，松开就发出去**，服务器转成文字、交给 AI 助手，
助手回话再由手机念出来。全程不依赖任何第三方云服务——转写、转发、朗读都在自己的机器上。

包里自带一个 SSH 隧道（JSch），所以**不需要装 Termux**：

```
按住绿球说话
   └→ 录音（m4a）
       └→ POST http://127.0.0.1:8790/voice   ← 本地端口，由 App 自己的 SSH 隧道接到你的服务器
           └→ 服务器转写 → 叫醒你的 AI 助手
助手回话
   └→ POST /reply/set 写到服务器
       └→ App 长轮询 GET /wait 取走 → 手机 TTS 念出来
```

## 它长什么样

- 桌面上一个绿色圆球，按住说话，拖动可贴边收成小条
- 通知栏两条：`语音球（按住绿圈说话）` 和 `语音球隧道（通着 127.0.0.1:8790）`
- 开机自启

## 服务端要准备什么

这个 App 只负责"录、传、取、念"，服务端要自己起。约定就三个接口（全部 HTTP，明文也行）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/voice` | 收到 m4a 音频，返回转写出来的文字；同时自己想办法叫醒 AI 助手 |
| POST | `/reply/set` | 助手把要说的话写进来（body 就是纯文本） |
| GET | `/wait` | 长轮询，等到有话说就返回纯文本（建议 50 秒超时后重连） |

服务端骨架见作者的 `voicebox_server.py`（Python 标准库就能跑），核心逻辑只有几十行。
注意：**语音口必须在服务器上只监听 `127.0.0.1`**，靠 SSH 隧道接进来，不要直接暴露公网。

## 编译

不依赖 Gradle，一个 PowerShell 脚本直接调 `javac` + `d8` + `aapt2` + `apksigner`：

```
# 先准备好这三样（版本随意，能对上就行）
#   JDK 8+            → <...>\android\build\jdk
#   Android SDK        → <...>\android\build\sdk
#                        build-tools/34.0.0 + platforms/android-34/android.jar
# 然后改 build-apk.ps1 顶部的 4 个路径，运行：
powershell -ExecutionPolicy Bypass -File build-apk.ps1
```

依赖：`jsch-0.1.55.jar`（放进 `lib/`，脚本会一起打成 dex）。

## 要改的配置

1. **服务器地址**：`src/com/yuhua/voicebox/TunnelService.java` 里的 `HOST`
2. **SSH 私钥**：二选一
   - 放进 `assets/voicebox_key`（打包进 APK，方便但等于把钥匙放进安装包）
   - 或者放到手机的 `/sdcard/Download/voicebox_key`（更安全，但安卓 10+ 读外部存储要权限）
3. 公钥加到服务器的 `~/.ssh/authorized_keys`

> 私钥格式：JSch 0.1.55 **不支持 ed25519**，请用
> `ssh-keygen -t rsa -b 2048 -m PEM -f voicebox_key` 生成。

## 踩过的坑（血泪版）

1. **网络请求不能放主线程**——会抛 `NetworkOnMainThreadException`，被 catch 吞掉后表现是"永远没反应"。
2. **安卓 10+ 读不了 `/sdcard` 任意文件**：`File.exists()` 为真、读的时候却 `FileNotFoundException`。
   所以私钥优先用打进包里那份，拷到应用私有目录再用。
3. **隧道放 App 里比放 Termux 里稳**：MIUI 一冻结 Termux 隧道就断；App 有自己的前台服务，
   再配合"自启动 + 省电无限制"基本不会被冻。
4. **JSch 0.1.55 够用**（连 OpenSSH 8.0 没问题）；换成新版 0.2.x 反而可能因为 class 版本太新在 d8 阶段报错。

## 许可

MIT。
