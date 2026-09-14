#!/usr/bin/env python3
"""子集化 Nebulove 字体并内嵌进 web/index.html"""
import re, base64, urllib.request, os, sys, tempfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT_URL = "https://raw.githubusercontent.com/lingyicute/Nebulove/main/Nebulove.ttf"
FALLBACK_URL = "https://cdn.jsdelivr.net/gh/lingyicute/Nebulove@main/Nebulove.woff2"
INDEX_PATH = os.path.join(REPO_ROOT, "web", "index.html")
# 下载超时（秒）：没有超时机制时，网络异常会让 CI 一直卡到 job 超时
DOWNLOAD_TIMEOUT_SEC = 60
# 匹配样式表里的 @font-face 块
FONT_FACE_RE = re.compile(r'@font-face\s*\{[^}]*\}', re.DOTALL)


def download(url, dest):
    """带超时的下载到文件（urllib.request.urlretrieve 无超时参数）"""
    req = urllib.request.Request(url, headers={"User-Agent": "webviewupdater-font-subset/1.0"})
    with urllib.request.urlopen(req, timeout=DOWNLOAD_TIMEOUT_SEC) as resp, open(dest, "wb") as out:
        while True:
            chunk = resp.read(64 * 1024)
            if not chunk:
                break
            out.write(chunk)


def main():
    if not os.path.exists(INDEX_PATH):
        print(f"Error: {INDEX_PATH} not found.", file=sys.stderr)
        sys.exit(1)

    print("Reading web/index.html...")
    with open(INDEX_PATH, "r", encoding="utf-8") as f:
        html = f.read()

    # 先确认页面里确实有 @font-face 可替换：否则下面的 re.sub 是空操作，
    # 却会输出「already up to date」，把「没找到替换点」误报成「已是最新」。
    matches = FONT_FACE_RE.findall(html)
    if not matches:
        print("Error: no @font-face block found in web/index.html, nothing to replace.",
              file=sys.stderr)
        sys.exit(1)
    if len(matches) > 1:
        print(f"Warning: found {len(matches)} @font-face blocks, only the first one is replaced.",
              file=sys.stderr)

    # 1. Collect all characters needed
    chars = set(html)
    # Ensure full ASCII printable set (32 to 126)
    for c in range(32, 127):
        chars.add(chr(c))
    # Common punctuation
    chars.update(['：', '，', '。', '！', '？', '；', '“', '”', '‘', '’', '（', '）', '【', '】', '—', '…', '·', '《', '》', '×', '＝', '÷', '＋', '－'])

    print(f"Total unique characters needed: {len(chars)}")

    with tempfile.TemporaryDirectory(prefix="nebulove-subset-") as workdir:
        tmp_font_path = os.path.join(workdir, "Nebulove.ttf")
        tmp_woff2 = os.path.join(workdir, "Nebulove-Subset.woff2")

        # 2. Download full Nebulove font
        print(f"Downloading font from {FONT_URL}...")
        try:
            download(FONT_URL, tmp_font_path)
        except Exception as e:
            print(f"Failed to download font: {e}", file=sys.stderr)
            sys.exit(1)

        # 3. Subset font using fontTools
        from fontTools.ttLib import TTFont
        from fontTools.subset import Subsetter, Options

        print("Subsetting font...")
        # recalcTimestamp=False：TTFont 默认为 True，见下面「固定时间戳」的说明
        font = TTFont(tmp_font_path, recalcTimestamp=False)
        subsetter = Subsetter(options=Options())
        # 排序是防御性措施：chars 是 set，其遍历顺序受字符串哈希随机化影响而随进程变化；
        # 排序后喂给 subsetter 的输入与进程无关（subsetter 结果本身与顺序无关，但少一个变量更好）。
        subsetter.populate(text="".join(sorted(chars)))
        subsetter.subset(font)

        # 固定时间戳，保证同样输入产出字节一致的 WOFF2，
        # 否则每次运行都会因为 head.modified 变化而产生无意义的 commit。
        # 注意：只改 head.modified 是不够的 —— fontTools 在 head 表 compile 时
        # 会执行 `if ttFont.recalcTimestamp: self.modified = timestampNow()`，
        # 把刚赋的值又覆盖成当前时间，于是每次产出的 WOFF2 字节都不同
        # （压缩后大小会差几十字节），diff 永远不为空，CI 每次都会提交一个
        # 「perf: 更新 Nebulove 字体子集」的空转 commit。
        # 必须同时关掉 recalcTimestamp，这个固定才真正生效。
        # 取 created 而不是 0：0 会被 fontTools 判定为异常小的时间戳并在
        # stderr 打 warning（读回时还会被加上 Mac epoch 偏移）。
        font["head"].modified = font["head"].created

        font.flavor = "woff2"
        font.save(tmp_woff2)

        woff2_size = os.path.getsize(tmp_woff2)
        print(f"Subsetted WOFF2 size: {woff2_size} bytes ({woff2_size / 1024:.2f} KB)")

        # 4. Convert to base64
        with open(tmp_woff2, "rb") as f:
            b64_font = base64.b64encode(f.read()).decode("utf-8")

    # 5. Replace font-face in web/index.html
    font_css = f'''@font-face{{
  font-family:"Nebulove";
  src:url("data:font/woff2;charset=utf-8;base64,{b64_font}") format("woff2"),
      url("{FALLBACK_URL}") format("woff2");
  font-weight:100 900;
  font-display:swap;
}}'''

    # 用函数作替换：re.sub 的字符串替换式会对 `\1`、`\g<name>` 之类的引用求值，
    # 把 font_css 当成模板；换成函数后原样写入，不受内容里的反斜杠影响。
    new_html = FONT_FACE_RE.sub(lambda _m: font_css, html, count=1)

    if new_html == html:
        print("web/index.html is already up to date. No changes made.")
        return

    with open(INDEX_PATH, "w", encoding="utf-8") as f:
        f.write(new_html)

    print("web/index.html updated successfully!")

if __name__ == "__main__":
    main()
