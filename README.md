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

**部署**（自己用 + 给朋友用，一个人一套服务）：[`docs/deployment.md`](docs/deployment.md)
**多微信号 / 多端的额度隔离**：[`docs/multi-account.md`](docs/multi-account.md)

---

## 快速开始

> **不懂技术？** 直接看 **[新手指南.md](新手指南.md)** ——
> Mac 上双击一个文件就能装，安卓的 APK 由 GitHub 自动编好可直接下载。

先对号入座，能省掉一大半工作：

| 你的设备 | 要装什么 | 说明 |
|---|---|---|
| **iPhone + Mac**（同一个微信号） | 只装 Mac 那份：`bash scripts/macos-setup.sh` | 消息两边都到，Mac 回了就够了。**不用 Appium，不用越狱**，`ios/` 可以整个忽略 |
| **只有 Android** | 装 APK 就完了 | 引擎内嵌在 App 里，不用电脑、不用服务器。想让回复像真人就在 App 里选 AI 模式并填一个 key |
| **只有 iPhone，没有 Mac** | 见可行性分析 | 不越狱的话要侧载改包微信，代价不小：[`docs/ios-feasibility.md`](docs/ios-feasibility.md) |

给别人用的话：**每个人跑各自的服务**，别共用——否则对方的聊天内容会流经你的日志，
回复也会用你的人设。详见 [`docs/deployment.md`](docs/deployment.md)。

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

---

## 让回复像真人，而不是像 QQ 自动回复

关键词匹配的问题不在于笨，在于它只能覆盖**你想得到的**情况。真人回消息靠的
是一套判断：我是谁、这事我怎么看、什么能答应什么不能。把这套东西写清楚交给
模型，才谈得上像人。

### 先答十道题，人设自己就出来了

写人设是这里最难的一步——对着「我是谁 / 我说话的方式 / 应对攻略」三个空框，
多数人会填几个「友好」「专业」这种空词，生成出来必然是客服腔。

但同样这个人，你问他「有人约你吃饭你一般怎么回」，张口就能答。所以：

```bash
python3 -m core.wizard          # 十道题，八道单选，一分钟答完
```

```
  你平时打字是什么感觉？
    1. 简短随便，跟熟人聊天那种
    2. 客气一点，会用「您」
    3. 热情，愿意多聊两句，爱开玩笑
    4. 能少说就少说，一两个字就完事
  > 1

  ── 生成结果 ──
  别人说「在吗」        → 在，怎么了
  别人约你吃饭          → 我看下日程，晚点回你
  别人问事情办得怎样    → 在弄了，这两天给你结果
```

一次问答同时生成两套：AI 模式的完整人设，和关键词模式的规则 + 兜底。
选哪种模式都不用自己想文案，而且两边语气一致。

答案是**按确定的规则拼装**的，不是喂给模型润色的——人设本身就是提示词，
不该是概率性的，而且这样才能写单测。安卓端有同一份实现
（装完第一次打开自动进问答页），两端的题目由单测钉住保持一致。

生成出来的就是下面这个东西，可以直接手改：

```yaml
reply_mode: ai        # ai / rules / rules_then_ai

persona:
  identity: |
    我是做独立开发的，白天基本埋在代码里，微信经常隔一两个小时才翻一次。

  tone: |
    句子短，口语，不用敬语，不说「您」，不用感叹号。熟人之间那种随便的语气。

  playbook: |                       # 最重要的一段
    有人问在不在：说在，但说明手上有事，等下回。
    有人约时间：一律说要确认日程，等我本人回，不要直接答应。
    有人问进度：给个模糊的时间感觉，不给具体日期，不打包票。
    看不懂或者事情重要：直接说等我本人回，不要硬猜着接话。

  boundaries: ["不谈具体报价", "不评价第三方的人和公司"]
  max_chars: 35

  examples:                         # 比任何形容词都管用，模型会直接模仿语气
    - {them: "在吗", me: "在，怎么了"}
    - {them: "明天有空不", me: "我看下日程，晚点回你"}
```

完整示例：[`core/config.ai.example.yaml`](core/config.ai.example.yaml)。

### 接哪家模型

```yaml
llm:
  provider: doubao        # 默认。key 从 ARK_API_KEY 读
```

| provider | 谁家 | key 从哪读 |
|---|---|---|
| `doubao` | 豆包（火山方舟） | `ARK_API_KEY` |
| `deepseek` | DeepSeek | `DEEPSEEK_API_KEY` |
| `qwen` | 通义千问 | `DASHSCOPE_API_KEY` |
| `zhipu` | 智谱 GLM | `ZHIPU_API_KEY` |
| `moonshot` | Moonshot | `MOONSHOT_API_KEY` |
| `anthropic` | Claude | `ANTHROPIC_API_KEY` |

**默认是豆包**：这套东西要的是「聊天像真人」而不是解数学题，
豆包的中文口语是这几家里最自然的，国内直连、注册即用。
Claude 那条路留着，但在国内拿 key 并不容易。

前五家都是 OpenAI 兼容接口，用标准库实现，装不装 `anthropic` 都能跑。
`model` 和 `base_url` 留空就用该家默认值。

> 火山方舟的 `model` 比较特殊：模型 ID（`doubao-seed-1-6-251015`）
> 和推理接入点（`ep-` 开头）都能填，而模型 ID 带日期后缀会随版本变。
> 报「模型名不对」时去控制台复制一个填进去。

**先在预览里调语气再上线**——你的联系人会看到这些话：

```bash
python3 -m core.preview        # 打字模拟对方发消息，不会真发出去
```

`reply_mode` 三选一：`ai` 全交给模型；`rules` 只用关键词；
`rules_then_ai` 规则优先、没命中才让模型写（有些话必须一字不差时用）。

### 安卓上的 AI：两种接法

手机上跑不了 Python 服务，所以引擎和人设都内嵌进了 APK。接模型有两条路，
设置页里二选一：

| | 自己的 key | 用别人给的地址 |
|---|---|---|
| 填什么 | 接口地址 + API Key + 模型名 | 地址 + 口令 |
| 打给谁 | 豆包 / DeepSeek / 千问 / 智谱 / Moonshot（预置好，点一下自动填） | 本仓库的 `server/app.py` |
| 依赖别人吗 | ❌ 不依赖 | ✅ 对方关机就不回了 |

第一条路是给「想自己独立用」的人准备的，国内直连、不用翻墙；第二条是给
「不想注册任何账号」的人准备的，代价是消息会经过对方服务器。
详见 [`docs/android-setup.md`](docs/android-setup.md)。

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
所有异常路径都返回「不回复」，宁可漏回不可乱发。AI 模式下生成失败也**不会**
悄悄退回规则文案：你选了 AI 就说明不想发罐头话，冒出一句风格完全不同的
句子比不回更糟。

**模型只决定「说什么」，不决定「该不该说」。** AI 分支排在敏感词、黑名单、
群聊策略、频率限制**之后**，所以含转账/验证码字样的消息压根不会被发给模型，
冷却期内的消息也不会白白调一次接口。这几条不能靠在提示词里写一句
「不要答应转账」了事——那是概率性的，而回错一次的代价太高。

**按账号隔离额度。** 限流的键是「账号 + 会话类型 + 归一化会话名」，
不是各端上报的 `chat_id`。跑两个不同的微信号时各算各的（默认按平台隔离，
不用额外配置）；同一个号在手机和电脑同时登录时，两端填相同的 `account`
即可共享冷却并去重，避免对方收到两条一样的回复。见
[`docs/multi-account.md`](docs/multi-account.md)。

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
core/           规则引擎（纯逻辑，60 个单测覆盖）
  engine.py       决策：安全检查 → 限流 → [AI | 规则] → 兜底
  config.py       YAML 解析 + 校验（配置写错会明确报哪一项）
  persona.py      人设、应对攻略、对话记忆、提示词拼装
  wizard.py       开场问答：十道题 → 整套回复内容
  providers.py    能接哪些模型服务（豆包/DeepSeek/千问/智谱/Moonshot）
  llm_openai.py   OpenAI 兼容接口生成（只用标准库）
  llm.py          Claude 生成（多轮上下文、拒答处理、超长截断）
  preview.py      终端预览：不发消息就能调语气
server/app.py   HTTP 服务，各端共用
ios/appium/     免越狱 UI 自动化
ios/tweak/      注入式插件（Logos）
android/        通知直接回复 + 无障碍兜底（Kotlin）
  engine/         规则引擎的 Kotlin 移植 + AI 生成器，全内嵌，不依赖服务器
macos/          辅助功能 API 代打
docs/           iOS 可行性分析、安卓搭建指南
```

跑测试：

```bash
python -m pytest core/tests/ -q
```
