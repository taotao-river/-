#!/bin/bash
#
# 双击这个文件就能装。不用会用终端。
#
# （Finder 里双击 .command 文件会自动打开「终端」并运行它，
#  所以使用者只需要双击，不需要自己敲任何命令。）

cd "$(dirname "$0")" || exit 1

# 出错时不要一闪而过关掉窗口，让人能看见错误信息
trap 'echo; echo "出错了。把上面红色的内容截图发给帮你配置的人。"; echo; read -n 1 -s -r -p "按任意键关闭..."' ERR

set -e

clear
cat <<'BANNER'
============================================================
             微信自动回复 —— Mac 安装程序
============================================================

这个程序会：
  1. 检查并准备运行环境
  2. 生成你的配置文件和密码
  3. 把「自动回复服务」设成开机自动启动

整个过程 2-5 分钟，中途可能需要你输入 Mac 的开机密码。

BANNER

read -n 1 -s -r -p "准备好了就按任意键开始（想退出就直接关窗口）..."
echo
echo

# ---------------------------------------------------------- 环境检查

echo "【1/5】检查运行环境"

if ! command -v python3 >/dev/null 2>&1; then
    echo
    echo "  缺少 Python，需要先装一下。"
    echo "  正在打开下载页面，请下载并安装«macOS 64-bit universal2 installer»，"
    echo "  装完之后重新双击本文件。"
    echo
    open "https://www.python.org/downloads/macos/"
    read -n 1 -s -r -p "按任意键关闭..."
    exit 0
fi
echo "      Python 有了 ($(python3 --version 2>&1))"

if [ ! -d "/Applications/WeChat.app" ]; then
    echo
    echo "  ⚠️  没找到 Mac 版微信。"
    echo "      请先去 App Store 装「微信」并登录，再回来双击本文件。"
    echo
    read -n 1 -s -r -p "按任意键关闭..."
    exit 0
fi
echo "      微信有了"

# ---------------------------------------------------------- 依赖

echo
echo "【2/5】准备运行环境（第一次会慢一点，请耐心等）"
VENV=".venv"
[ -d "$VENV" ] || python3 -m venv "$VENV"
"$VENV/bin/pip" install --quiet --upgrade pip
"$VENV/bin/pip" install --quiet fastapi "uvicorn[standard]" pyyaml requests
echo "      好了"

# ---------------------------------------------------------- 配置

echo
echo "【3/5】生成你的回复内容"
if [ -f core/config.yaml ]; then
    echo "      配置文件已存在，保留你之前改过的内容"
    echo "      想重新答一遍：$VENV/bin/python -m core.wizard"
else
    # 问几个问题把整套话生成出来。直接抄一份示例配置，
    # 结果就是所有人的自动回复长得一模一样——那还不如不回。
    if ! "$VENV/bin/python" -m core.wizard; then
        echo
        echo "      跳过了问答，先放一份示例配置"
        cp core/config.example.yaml core/config.yaml
        echo "      想以后再答：$VENV/bin/python -m core.wizard"
    fi
fi

if [ ! -f .wxauto_token ]; then
    python3 -c "import secrets; print(secrets.token_hex(16))" > .wxauto_token
    chmod 600 .wxauto_token
fi
TOKEN="$(cat .wxauto_token)"
mkdir -p var

# ---------------------------------------------------------- 后台服务

echo
echo "【4/5】设置开机自动启动"
REPO_DIR="$(pwd)"
PLIST="$HOME/Library/LaunchAgents/com.wxauto.server.plist"
mkdir -p "$HOME/Library/LaunchAgents"

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>com.wxauto.server</string>
    <key>ProgramArguments</key>
    <array>
        <string>${REPO_DIR}/.venv/bin/python</string>
        <string>-m</string><string>uvicorn</string>
        <string>server.app:app</string>
        <string>--host</string><string>127.0.0.1</string>
        <string>--port</string><string>8848</string>
    </array>
    <key>WorkingDirectory</key><string>${REPO_DIR}</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>WXAUTO_CONFIG</key><string>core/config.yaml</string>
        <key>WXAUTO_STATE</key><string>var/state.json</string>
        <key>WXAUTO_TOKEN</key><string>${TOKEN}</string>
    </dict>
    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><true/>
    <key>StandardOutPath</key><string>${REPO_DIR}/var/server.log</string>
    <key>StandardErrorPath</key><string>${REPO_DIR}/var/server.err.log</string>
</dict>
</plist>
EOF

launchctl unload "$PLIST" 2>/dev/null || true
launchctl load "$PLIST"
sleep 3

if curl -sf --max-time 5 http://127.0.0.1:8848/health >/dev/null 2>&1; then
    echo "      服务已启动"
else
    echo "      ⚠️ 服务没起来，稍后可以把 var/server.err.log 发给帮你配置的人"
fi

# ---------------------------------------------------------- 启动器

echo
echo "【5/5】生成桌面快捷方式"

# 文件名带序号：不写清楚先点哪个，多数人会直接点最后那个
cat > "1 检查微信.command" <<EOF
#!/bin/bash
cd "\$(dirname "\$0")"
clear
echo "=========================================================="
echo "  检查程序能不能正常读到微信的界面"
echo "  这一步只看不动：不读消息，也不发消息"
echo "=========================================================="
echo
"$REPO_DIR/.venv/bin/python" macos/wechat_mac_bot.py --doctor
echo
echo "=========================================================="
echo "  上面如果全是 [OK]，就可以双击「2 试运行」了。"
echo "  如果出现 [X ]，把这个窗口整个截图发给帮你配置的人。"
echo "=========================================================="
echo
read -n 1 -s -r -p "按任意键关闭..."
EOF

cat > "2 试运行（不真发消息）.command" <<EOF
#!/bin/bash
cd "\$(dirname "\$0")"
export WXAUTO_SERVER=http://127.0.0.1:8848
export WXAUTO_TOKEN="$TOKEN"
clear
echo "=========================================================="
echo "  试运行模式：只显示「本来会回什么」，不会真的发出去"
echo
echo "  注意：读消息需要点开会话，所以未读会被标成已读。"
echo "  确认没问题后，再用「3 开始自动回复」那个文件"
echo "  想停止：按 Control + C，或直接关掉这个窗口"
echo "=========================================================="
echo
exec caffeinate -i "$REPO_DIR/.venv/bin/python" macos/wechat_mac_bot.py --dry-run
EOF

cat > "3 开始自动回复.command" <<EOF
#!/bin/bash
cd "\$(dirname "\$0")"
export WXAUTO_SERVER=http://127.0.0.1:8848
export WXAUTO_TOKEN="$TOKEN"
clear
echo "=========================================================="
echo "  自动回复运行中，会真的发消息了。"
echo "  这个窗口关掉就停了。"
echo "  想停止：按 Control + C，或直接关掉这个窗口"
echo "=========================================================="
echo
exec caffeinate -i "$REPO_DIR/.venv/bin/python" macos/wechat_mac_bot.py
EOF

chmod +x "1 检查微信.command" "2 试运行（不真发消息）.command" "3 开始自动回复.command"
echo "      好了"

# ---------------------------------------------------------- 授权引导

echo
echo "============================================================"
echo "  安装完成！但还差最后一步授权，不做的话读不到微信消息"
echo "============================================================"
echo
echo "  现在会自动打开「辅助功能」设置页面。请在里面："
echo
echo "     找到「终端」并把开关打开"
echo "     （如果列表里没有「终端」，点下面的 ➕ 号，"
echo "       从「应用程序 → 实用工具」里选「终端」）"
echo
read -n 1 -s -r -p "按任意键打开设置页面..."
open "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"
echo
echo
echo "------------------------------------------------------------"
echo "  授权完成后，回到这个文件夹，按数字顺序双击三个文件："
echo
echo "     「1 检查微信.command」"
echo "        看程序能不能读到微信界面。只看不动，很安全。"
echo "        全是 [OK] 就继续；出现 [X ] 就截图求助。"
echo
echo "     「2 试运行（不真发消息）.command」"
echo "        看它会回什么，但不会真的发出去。"
echo
echo "     「3 开始自动回复.command」"
echo "        确认前两步都没问题后再用这个，它会真的发消息。"
echo
echo "  另外建议：系统设置 → 电池 → 把「睡眠」设成「永不」，"
echo "           否则合上盖子自动回复就停了。"
echo "------------------------------------------------------------"
echo
read -n 1 -s -r -p "按任意键关闭..."
