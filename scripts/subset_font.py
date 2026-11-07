#!/usr/bin/env python3
"""子集化 Nebulove 字体并内嵌进 web/index.html"""
import re, base64, urllib.request, os, sys, tempfile, html as html_lib

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT_URL = "https://raw.githubusercontent.com/lingyicute/Nebulove/main/Nebulove.ttf"
FONT_MIRROR_URL = "https://cdn.jsdelivr.net/gh/lingyicute/Nebulove@main/Nebulove.ttf"
FALLBACK_URL = "https://cdn.jsdelivr.net/gh/lingyicute/Nebulove@main/Nebulove.woff2"
INDEX_PATH = os.path.join(REPO_ROOT, "web", "index.html")
# 下载超时（秒）：没有超时机制时，网络异常会让 CI 一直卡到 job 超时
DOWNLOAD_TIMEOUT_SEC = 60
# 匹配样式表里的 @font-face 块及 HTML / CSS / JS 注释（保留字符串字面量防止误删）
FONT_FACE_RE = re.compile(r'@font-face\s*\{[^}]*\}', re.DOTALL)
HTML_COMMENT_RE = re.compile(r'<!--.*?-->', re.DOTALL)
STYLE_TAG_RE = re.compile(r'(<style\b[^>]*>)(.*?)(</style>)', re.DOTALL | re.IGNORECASE)
CSS_TOKEN_RE = re.compile(r'("(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\')|/\*.*?\*/', re.DOTALL)
SCRIPT_TAG_RE = re.compile(r'(<script\b[^>]*>)(.*?)(</script>)', re.DOTALL | re.IGNORECASE)
JS_TOKEN_RE = re.compile(
    r'("(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|`(?:\\.|[^`\\])*`)|/\*.*?\*/|//[^\r\n]*',
    re.DOTALL,
)


def download(url, dest):
    """带超时的下载到文件（urllib.request.urlretrieve 无超时参数），并校验 Content-Length 完整性"""
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "webviewupdater-font-subset/1.0",
            "Accept-Encoding": "identity",
        },
    )
    with urllib.request.urlopen(req, timeout=DOWNLOAD_TIMEOUT_SEC) as resp, open(dest, "wb") as out:
        expected_header = resp.headers.get("Content-Length")
        total_read = 0
        while True:
            chunk = resp.read(64 * 1024)
            if not chunk:
                break
            out.write(chunk)
            total_read += len(chunk)
    if total_read <= 0:
        raise IOError("Downloaded font file is empty")
    if expected_header is not None:
        try:
            expected_size = int(expected_header.strip())
        except ValueError:
            expected_size = -1
        if expected_size > 0 and total_read != expected_size:
            raise IOError(f"Incomplete font download: got {total_read} bytes, expected {expected_size}")


def main():
    if not os.path.exists(INDEX_PATH):
        print(f"Error: {INDEX_PATH} not found.", file=sys.stderr)
        sys.exit(1)

    print("Reading web/index.html...")
    with open(INDEX_PATH, "r", encoding="utf-8") as f:
        html = f.read()

    # 先确认 <style> 块里确实有 @font-face 可替换：否则下面的替换是空操作，
    # 却会输出「already up to date」，把「没找到替换点」误报成「已是最新」。
    matches = [
        m
        for style_m in STYLE_TAG_RE.finditer(html)
        for m in FONT_FACE_RE.findall(style_m.group(2))
    ]
    if not matches:
        print("Error: no @font-face block found in web/index.html, nothing to replace.",
              file=sys.stderr)
        sys.exit(1)
    if len(matches) > 1:
        print(f"Warning: found {len(matches)} @font-face blocks, only the first one is replaced.",
              file=sys.stderr)

    # 1. Collect all characters needed (exclude old @font-face data URI, comments & unescape HTML entities)
    html_without_comments = HTML_COMMENT_RE.sub("", html)
    html_without_comments = STYLE_TAG_RE.sub(
        lambda m: m.group(1)
        + CSS_TOKEN_RE.sub(lambda tm: tm.group(1) or "", FONT_FACE_RE.sub("", m.group(2)))
        + m.group(3),
        html_without_comments,
    )
    html_without_comments = SCRIPT_TAG_RE.sub(
        lambda m: m.group(1)
        + JS_TOKEN_RE.sub(lambda tm: tm.group(1) or "", m.group(2))
        + m.group(3),
        html_without_comments,
    )
    chars = set(html_lib.unescape(html_without_comments))
    # Ensure full ASCII printable set (32 to 126)
    for c in range(32, 127):
        chars.add(chr(c))
    # Common punctuation
    chars.update(['：', '，', '。', '！', '？', '；', '“', '”', '‘', '’', '（', '）', '【', '】', '—', '…', '·', '《', '》', '×', '＝', '÷', '＋', '－'])
    # Strip control characters (such as \n, \r, \t)
    chars = {ch for ch in chars if ord(ch) >= 32 and not (0x7F <= ord(ch) <= 0x9F)}

    print(f"Total unique characters needed: {len(chars)}")

    from fontTools.ttLib import TTFont
    from fontTools.subset import Subsetter, Options

    sorted_text = "".join(sorted(chars))

    with tempfile.TemporaryDirectory(prefix="nebulove-subset-") as workdir:
        tmp_font_path = os.path.join(workdir, "Nebulove.font")
        tmp_woff2 = os.path.join(workdir, "Nebulove-Subset.woff2")

        # 2 & 3. Download full Nebulove font and subset with mirror fallback.
        # TTFont 默认惰性加载各表，仅访问 head 无法发现 glyf/cmap 等表损坏；
        # 将完整子集化与 WOFF2 导出纳入回退循环，避免非 head 表损坏时漏触发镜像回退，也无需重复解析字体。
        sources = (FONT_URL, FONT_MIRROR_URL, FALLBACK_URL)
        last_err = None
        for idx, url in enumerate(sources):
            try:
                for p in (tmp_font_path, tmp_woff2):
                    if os.path.exists(p):
                        os.remove(p)
                print(f"Downloading font from {url}...")
                download(url, tmp_font_path)
                print("Subsetting font...")
                # recalcTimestamp=False：TTFont 默认为 True，见下面「固定时间戳」的说明
                with TTFont(tmp_font_path, recalcTimestamp=False) as font:
                    subsetter = Subsetter(options=Options())
                    # 排序是防御性措施：chars 是 set，其遍历顺序受字符串哈希随机化影响而随进程变化；
                    # 排序后喂给 subsetter 的输入与进程无关（subsetter 结果本身与顺序无关，但少一个变量更好）。
                    subsetter.populate(text=sorted_text)
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
                if woff2_size <= 0:
                    raise IOError("Subsetted WOFF2 file is empty")
                last_err = None
                break
            except Exception as err:
                last_err = err
                if idx + 1 < len(sources):
                    print(f"Font source {url} failed ({err}), trying fallback {sources[idx + 1]}...",
                          file=sys.stderr)
        if last_err is not None:
            print(f"Failed to download and subset valid font: {last_err}", file=sys.stderr)
            sys.exit(1)

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

    # 仅替换首个包含 @font-face 的 <style> 块中的首个 @font-face；
    # 用函数作替换避免 re.sub 将替换串当成反斜杠模板求值。
    replaced = False

    def replace_in_style(m):
        nonlocal replaced
        if replaced or not FONT_FACE_RE.search(m.group(2)):
            return m.group(0)
        replaced = True
        return m.group(1) + FONT_FACE_RE.sub(lambda _m: font_css, m.group(2), count=1) + m.group(3)

    new_html = STYLE_TAG_RE.sub(replace_in_style, html)

    if new_html == html:
        print("web/index.html is already up to date. No changes made.")
        return

    tmp_html_fd, tmp_html_path = tempfile.mkstemp(
        prefix=".index.html.", suffix=".tmp", dir=os.path.dirname(INDEX_PATH)
    )
    try:
        with os.fdopen(tmp_html_fd, "w", encoding="utf-8", newline="\n") as f:
            f.write(new_html)
        os.replace(tmp_html_path, INDEX_PATH)
    except Exception:
        if os.path.exists(tmp_html_path):
            os.remove(tmp_html_path)
        raise

    print("web/index.html updated successfully!")

if __name__ == "__main__":
    main()
