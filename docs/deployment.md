# 部署：给自己用，也给别人用

## 一条铁律：一个人一套服务

**不要让别人连你的规则服务。** 这不是洁癖，是三个实打实的问题：

1. **隐私**。所有消息都会 POST 到服务端，日志里也会写。别人连你的服务，
   等于他的微信聊天内容全流经你的机器、进你的日志。
2. **人设串台**。规则和文案是服务端全局的。他的联系人会收到你写的
   「我在忙，稍后回你」，落款还是你的语气。
3. **token 等于代发权**。同一个 token 谁拿到都能驱动引擎。共享 token
   意味着对方（或拿到他手机的人）能往你的服务里灌任意消息。

所以：**各跑各的服务，各用各的配置、token 和状态文件。**
下面按人分开说。

---

## 你：iPhone + Mac

### iPhone 上什么都不用装

iPhone 和 Mac 登的是同一个微信号，消息两边都会到。Mac 上的 bot 回了，
对方就收到了——iPhone 再跑一份只会重复。

这直接省掉了两条最麻烦的路：**不用 Appium**（要占着手机、锁屏就停），
**也不用越狱**。`ios/` 目录你可以整个忽略。

### Mac 上跑两个东西

```bash
# 一次性准备
cp core/config.example.yaml core/config.yaml   # 改成你的规则
echo "export WXAUTO_TOKEN=$(openssl rand -hex 16)" >> ~/.zshrc
source ~/.zshrc
```

```bash
# 终端 1：规则服务
export WXAUTO_CONFIG=core/config.yaml
uvicorn server.app:app --port 8848
```

```bash
# 终端 2：采集端
export WXAUTO_SERVER=http://127.0.0.1:8848
python macos/wechat_mac_bot.py --dry-run    # 先干跑几轮
python macos/wechat_mac_bot.py
```

注意这里用默认的 `127.0.0.1` 就够了——服务和采集端都在 Mac 上，
**不需要 `--host 0.0.0.0`**，也就不用担心局域网里其他人能访问。

别忘了给终端授权：系统设置 → 隐私与安全性 → 辅助功能。

### 如果你出门也想让 iPhone 顶上

Mac 关机时 iPhone 才有意义。真要这么做的话：

- iPhone 走 `ios/appium/`（需要 Mac 常插着，出门场景其实不成立）
  或 `ios/tweak/`（需要越狱）
- **两端必须填相同的 `WXAUTO_ACCOUNT`**，否则同一条消息会被回两次

```bash
# Mac
export WXAUTO_ACCOUNT=我
# iPhone (Appium)
export WXAUTO_ACCOUNT=我
```

老实说：为了「出门那几小时」去折腾越狱或者常驻 Appium，性价比很低。
更实际的做法是 Mac 别关机。

---

## 你朋友：只有 Android

他的难点是**没有地方跑规则服务**。三个选择，从推荐到不推荐：

### 方案一：服务跑在手机上（推荐）

用 Termux 在安卓本机跑 Python 服务，App 走 `127.0.0.1` 连它。
不依赖电脑、不依赖局域网、不怕换 Wi-Fi。

```bash
# 从 F-Droid 装 Termux（Google Play 版已停止维护，会缺包）
# 把仓库拷到手机上，然后：
bash scripts/termux-setup.sh
./run-server.sh
```

脚本会装依赖、生成配置和 token、写好启动脚本，最后把要填进 App 的
地址和 token 打印出来。开机自启需要额外装 Termux:Boot。

几个注意点：

- 服务只监听 `127.0.0.1`，不暴露到局域网——App 和服务在同一台手机上，
  没必要开这个口。
- 脚本装的是 `uvicorn` 而不是 `uvicorn[standard]`：后者要在手机上编译
  C 扩展，容易失败，而这点性能这里用不上。
- `termux-wake-lock` 防止系统休眠把服务杀掉。省电策略激进的 ROM
  （小米、华为等）还要手动把 Termux 和微信都加进白名单。

### 方案二：他自己的电脑上跑

如果他有常开的电脑，和你的 Mac 方案一样，只是 App 里填电脑的局域网 IP，
服务要用 `--host 0.0.0.0` 启动。缺点是**换到别的 Wi-Fi 就断**。

### 方案三：连你的服务（不推荐）

除非你俩都清楚上面「一条铁律」里那三个问题并且都接受，否则别这么做。

如果确实要（比如你在帮他调试），至少：给他单独生成一个 token 而不是
共用你的，调完立刻换掉。

---

## 给朋友之前，先说清楚

这套东西装到别人手机上，有些话你替他确认比他自己发现要好：

- **权限很大**。通知使用权能读到他手机上所有 App 的通知。代码里只处理
  `com.tencent.mm`、也不落盘存消息，但这是靠代码自觉，不是系统限制。
  让他装**你自己编译的** APK，别从别处下。
- **有封号风险**。自动化行为可能触发微信风控。引擎默认带了随机延迟、
  单会话冷却、每日上限，但不能保证不被判定。
- **默认会带「（自动回复）」后缀**。建议保留，让对方知道在跟机器说话。
- **敏感词不会自动回**。转账、红包、验证码、借钱这些一律交给人工，
  这个行为不可关闭。
- **建议先用白名单**。`scope.allow_contacts` 只对确定的几个人开，
  跑顺了再放开，比一上来对所有人全天开安全得多。

---

## 各自的配置起点

两个人的使用场景多半不一样，配置也不该照抄。

**你（Mac 常开，可能是工作用）**

```yaml
active_hours: ["09:00-12:00", "13:30-22:00"]
scope:
  reply_to_group: only_at_me
  block_contacts: ["老板", "家人"]
limits:
  per_chat_cooldown_seconds: 1800
```

**你朋友（手机随身，可能全天在线）**

```yaml
active_hours: []              # 全天，但靠冷却控频率
scope:
  reply_to_group: never       # 手机上群消息多，先全关掉
limits:
  per_chat_cooldown_seconds: 3600   # 冷却拉长，减少打扰
  max_replies_per_chat_per_day: 3
```

两边都建议先跑 `--dry-run`（macOS）或看 `adb logcat`（安卓）观察几天，
确认规则命中符合预期，再真正放开。
