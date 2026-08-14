# 微信消息自动回复

一套跨平台的微信自动回复方案。规则引擎只写一份，iOS / Android / macOS
各端只负责「抓消息」和「按发送键」。

```
                   ┌─────────────────────────┐
  iOS (Appium)  ───┤                         │
  iOS (Tweak)   ───┤   规则引擎 (Python)      │
  Android       ───┤   规则 / 冷却 / 敏感词    │──→ 可选：Claude 生成回复
  macOS         ───┤   HTTP :8848            │
                   └─────────────────────────┘
```

---

## 先回答你的问题：iOS 能做吗？

**能，但「装个 App 就自动回微信」在未越狱 iPhone 上不存在。**
iOS 沙盒不允许任何第三方 App 读取或发送微信消息——没有通知监听 API，
快捷指令拿不到微信消息，扩展也碰不到别的 App。这是设计如此，不是绕不绕得过的问题。

真正能跑的有三条路，本仓库**三条都实现了**：

| 路线 | 要越狱吗 | 手机能正常用吗 | 后台常驻 | 实现 |
|---|---|---|---|---|
| **A. UI 自动化**（Appium + WDA） | 否，但要 Mac + 真机 | ❌ 微信要一直在前台 | ❌ 锁屏就停 | [`ios/appium/`](ios/appium/) |
| **B. 注入插件**（Theos tweak） | 是（或重签名 IPA） | ✅ | ✅ | [`ios/tweak/`](ios/tweak/) |
| **C. macOS 代打** | 否 | ✅ 完全不占用手机 | ✅ Mac 开着就行 | [`macos/`](macos/) |

> 路线 C 常被忽略但最实用：微信支持手机和 Mac 同时在线，收的是同一份消息。
> Mac 上自动回复，对方看到的效果和你用 iPhone 回一模一样。
> **如果你的目标是「我的微信能自动回消息」而不是「必须在 iPhone 上跑代码」，直接看路线 C。**

完整分析（含每种"想当然"的做法为什么行不通）：**[`docs/ios-feasibility.md`](docs/ios-feasibility.md)**

安卓上这件事简单得多（系统有官方 API，不用 root）：[`docs/android-setup.md`](docs/android-setup.md)

---

## 快速开始

### 1. 起规则服务

```bash
pip install -r requirements.txt

cp core/config.example.yaml core/config.yaml   # 按需改规则
export WXAUTO_CONFIG=core/config.yaml
export WXAUTO_TOKEN=$(openssl rand -hex 16)    # 记下这个值
uvicorn server.app:app --host 0.0.0.0 --port 8848
```

### 2. 挑一个端接上

```bash
# macOS（最省事）
export WXAUTO_TOKEN=<上面那个值>
python macos/wechat_mac_bot.py --dry-run       # 先干跑看看

# iOS 免越狱          → ios/appium/README.md
# iOS 插件            → ios/tweak/README.md
# Android             → docs/android-setup.md
```

**每个端都支持 `--dry-run`：只打印「本应回复什么」，不真发。
第一次用请务必先干跑几轮。**

---

## 规则怎么写

全在一个 YAML 里，改完调 `POST /reload` 即可生效，不用重启：

```yaml
active_hours: ["09:00-12:00", "13:30-22:00"]   # 只在这些时段回

scope:
  reply_to_group: only_at_me      # 群聊只在被 @ 时回
  block_contacts: ["老板", "妈妈"]  # 这些人永不自动回

limits:
  per_chat_cooldown_seconds: 1800      # 同一会话 30 分钟内只回一次
  max_replies_per_chat_per_day: 5
  global_max_replies_per_hour: 30      # 规则写错时的保险丝
  min_delay_seconds: 3                 # 随机延迟，别秒回
  max_delay_seconds: 12

rules:
  - name: 打招呼
    match: {type: keyword, any: ["在吗", "在么", "忙吗"]}
    reply:                             # 列表会轮换，避免反复发同一句
      - "在的，我这会儿在忙，稍后回你"
      - "在，手上有点事，等下详细说"

  - name: 问进度
    match: {type: regex, pattern: "进度|做完了吗|什么时候"}
    reply: "还在推进，今天之内给你结果"

fallback:
  type: text                          # text / llm / none
  text: "我现在不方便，看到会尽快回你"
```

完整字段说明见 [`core/config.example.yaml`](core/config.example.yaml) 里的注释。

### 让 Claude 现场生成回复

规则没命中时，可以让模型按你的人设写一句：

```yaml
fallback:
  type: llm

llm:
  model: claude-opus-5
  effort: low                # 自动回复要的是低延迟，不是深度推理
  persona: "一个做设计外包的自由职业者，说话简短随和"
  style: "口语化中文，不超过 30 个字，不用感叹号"
```

需要 `export ANTHROPIC_API_KEY=...`（或先跑 `ant auth login`）。
系统提示里已经硬性约束了：不承诺金额时间地点、不答应转账借钱、
拿不准就回「我稍后本人回复你」。

---

## 内置的几道保险

自动回复最大的风险不是不回，是**回错**。所以引擎里有几条不可关闭的规则：

**硬性敏感词永不自动回复。** 转账、红包、验证码、银行卡、身份证、密码、
借钱、急用钱、汇款、付款码——命中任何一个就交给人工。这个判断排在所有
频率逻辑**之前**，所以哪怕你把冷却调成 0、规则写成全匹配，也拦得住。

**默认加「（自动回复）」后缀。** 让对方知道在跟机器说话，是最省事的防误会手段。
可以关，但建议留着。

**三层限流。** 单会话冷却 + 单会话每日上限 + 全局每小时上限。
最后一层是保险丝——规则写错时最多刷 30 条，不会失控。

**随机延迟。** 秒回是最明显的机器特征，也最容易触发风控。默认 3–12 秒随机。

**失败一律不发。** 规则服务不可达、LLM 生成失败、读不到消息内容——
所有异常路径都返回「不回复」，宁可漏回不可乱发。

---

## 风控与合规

这些方案都在做微信官方没有开放的事，客观上存在风险，需要你自己判断：

- **账号风控**：自动化行为可能触发微信风控，轻则功能受限，重则封号。
  上面那些限流和延迟就是为了把行为拉回「像人」的范围，但**不能保证不被判定**。
- **越狱 / 重签名**会降低设备安全性，也违反微信用户协议。
- **无障碍权限和通知使用权都非常大**，能读到手机上所有内容。
  只装你自己编译的版本。

建议的用法：**用 `allow_contacts` 白名单只对确定的人开**，
而不是对所有人全天开启。

---

## 项目结构

```
core/           规则引擎（纯逻辑，34 个单测覆盖）
  engine.py       决策：匹配 → 安全检查 → 限流 → 生成
  config.py       YAML 解析 + 校验（配置写错会明确报哪一项）
  llm.py          Claude 兜底生成
server/app.py   HTTP 服务，各端共用
ios/appium/     免越狱 UI 自动化
ios/tweak/      注入式插件（Logos）
android/        通知直接回复 + 无障碍兜底（Kotlin）
macos/          辅助功能 API 代打
docs/           iOS 可行性分析、安卓搭建指南
```

跑测试：

```bash
python -m pytest core/tests/ -q
```
