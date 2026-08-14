# 同时跑 Android + macOS

这是最常见的组合：Mac 常开当主力，手机在外面时兜底。但**两端同时在线
会踩一个坑**，先说清楚。

## 坑：重复回复

微信支持手机和 Mac 同时在线，收到的是同一条消息。如果两端的自动回复
都开着，对方会收到**两条一模一样的回复**。

引擎已经处理了这件事，靠两层：

1. **按「会话身份」记账**。限流的键是归一化后的会话名
   （`private:小王`），而不是各端上报的 `chat_id`——安卓传
   `android:com.tencent.mm:小王`、macOS 传 `macos:小王`，用 chat_id
   会分裂成两套独立冷却。归一化后两端共享同一份冷却和每日额度。
2. **内容去重窗口**。`cross_device_dedup_seconds`（默认 120 秒）内，
   同一会话的相同内容只处理一次。日志里会明确写「另一端已处理过」，
   而不是含糊的「冷却中」。

**前提是两端必须连同一个规则服务。** 各自跑一个 server 就等于两套独立
状态，去重不起作用。

## 建议的部署方式

让 Mac 同时扮演两个角色——规则服务 + macOS 采集端，安卓通过局域网连过来：

```
        ┌─────────── Mac（常开）───────────┐
        │  uvicorn server.app:app :8848    │
        │  wechat_mac_bot.py               │
        └──────────────┬───────────────────┘
                       │ 局域网 http://192.168.1.10:8848
                ┌──────┴──────┐
                │  Android App │
                └─────────────┘
```

### 1. Mac 上起服务

```bash
export WXAUTO_CONFIG=core/config.yaml
export WXAUTO_TOKEN=$(openssl rand -hex 16)   # 记下来，安卓要填同一个
uvicorn server.app:app --host 0.0.0.0 --port 8848
```

注意是 `--host 0.0.0.0` 而不是默认的 `127.0.0.1`，否则手机连不上。

### 2. Mac 上起采集端

```bash
export WXAUTO_SERVER=http://127.0.0.1:8848
export WXAUTO_TOKEN=<同上>
python macos/wechat_mac_bot.py --dry-run    # 先干跑
```

### 3. 安卓填 Mac 的局域网 IP

App 里地址填 `http://192.168.1.10:8848`（换成 `ipconfig getifaddr en0`
查到的实际 IP），token 填同一个。

先在手机浏览器访问 `http://192.168.1.10:8848/health` 确认通不通。

## 让 Mac 上的服务开机自启

存成 `~/Library/LaunchAgents/com.wxauto.server.plist`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.wxauto.server</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/bin/env</string>
        <string>python3</string>
        <string>-m</string>
        <string>uvicorn</string>
        <string>server.app:app</string>
        <string>--host</string>
        <string>0.0.0.0</string>
        <string>--port</string>
        <string>8848</string>
    </array>
    <key>WorkingDirectory</key>
    <string>/Users/你的用户名/path/to/repo</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>WXAUTO_CONFIG</key>
        <string>core/config.yaml</string>
        <key>WXAUTO_TOKEN</key>
        <string>你的token</string>
    </dict>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardErrorPath</key>
    <string>/tmp/wxauto.err.log</string>
</dict>
</plist>
```

```bash
launchctl load ~/Library/LaunchAgents/com.wxauto.server.plist
```

> `wechat_mac_bot.py` 不建议这样自启——它需要辅助功能权限，而 launchd
> 拉起的进程权限归属容易出问题。手动在终端里跑更省心。

## 两端的分工建议

两端能力不一样，可以按场景分：

| | Android | macOS |
|---|---|---|
| 微信要前台吗 | ❌ 不用 | ✅ 要 |
| 免打扰的会话 | ❌ 收不到（通知方案） | ✅ 能扫到 |
| 长消息 | 通知可能截断 | 完整 |
| 出门在外 | ✅ | ❌ |

一个实用的做法：**Mac 在的时候以 Mac 为准，出门只留安卓**。
不想两端同时跑的话，直接把不用的那端停掉，比依赖去重更干净。

## 排查

**对方收到了两条回复**
两端连的不是同一个服务。检查安卓 App 里的地址是不是指向 Mac，
以及 Mac 上是不是不小心起了两个 server 实例（`lsof -i :8848`）。

**日志里全是「跨端去重」，一条都没发出去**
说明另一端抢先处理了——这是正常的，去另一端的日志里看是不是发出去了。

**改了 chat_name 归一化规则后冷却计数不对**
状态文件里的键是旧格式。删掉 `var/state.json` 重来即可，
代价只是冷却计数清零。
