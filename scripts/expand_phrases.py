# -*- coding: utf-8 -*-
"""Expand each CommentaryPhrases bank to ~2x current size (add variants)."""
from __future__ import annotations

import re
from pathlib import Path

PATH = Path(
    r"c:\Users\nam.nguyen19\Documents\prj\pickleball\vdpr\duprvn-video"
    r"\app\src\main\java\com\pickleball\video\commentary\CommentaryPhrases.kt"
)

OPENERS = [
    "Thật vậy,", "Nhìn xem,", "Đúng lúc này,", "Trên sân lúc này,", "Xin nhắc lại,",
    "Một diễn biến mới,", "Cập nhật nhanh,", "Theo dõi tiếp,", "Không khí đang đổi,",
    "Vừa rồi,", "Lúc này quý vị,", "Đáng chú ý,", "Xin hãy lắng nghe,", "Phút giây này,",
    "Diễn biến nóng,", "Trực tiếp từ sân,", "Bình luận viên xin phép,", "Góc nhìn nhanh,",
]

TAILS = [
    " Hãy cùng dõi theo tiếp.",
    " Không khí vẫn rất đáng xem.",
    " Xin giữ tinh thần cổ vũ văn minh.",
    " Bản lĩnh sẽ quyết định đoạn tới.",
    " Cảm xúc sân còn rất cao.",
    " Đừng bỏ lỡ những pha tiếp theo.",
    " Trận đấu vẫn còn nhiều bất ngờ.",
    " Loa bình luận cũng đang nóng theo.",
    " Khán đài đang rất tập trung.",
    " Một nhịp nữa sẽ rõ hơn.",
]

SWAPS = [
    ("quý vị", "mọi người"), ("trời ơi", "ôi giời"), ("bảng điểm", "bảng tỉ số"),
    ("hiện tại", "lúc này"), ("tỉ số", "điểm số"), ("khán đài", "khán giả"),
    ("chiến thắng", "thắng lợi"), ("kết thúc", "khép sổ"), ("trận đấu", "cuộc chơi"),
    ("gay cấn", "căng thẳng"), ("bản lĩnh", "đẳng cấp"), ("đáng xem", "đáng tiền"),
]


def extract_strings(block: str) -> list[str]:
    return re.findall(r'"((?:\\.|[^"\\])*)"', block)


def escape_kt(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"')


def ensure_sentence(s: str) -> str:
    s = re.sub(r"\s{2,}", " ", s).strip().replace("..", ".")
    if s and s[-1] not in ".!?":
        s += "."
    return s


def soften(s: str) -> str:
    if not s or s.startswith("{"):
        return s
    return s[0].lower() + s[1:] if s[0].isupper() else s


def swap_once(s: str, idx: int) -> str:
    out = s
    for i, (a, b) in enumerate(SWAPS):
        if (i + idx) % 2 == 0 and a in out:
            out = out.replace(a, b, 1)
            break
    return out


def one_variant(base: str, seed: int) -> str:
    mode = seed % 4
    if mode == 0:
        cand = f"{OPENERS[seed % len(OPENERS)]} {soften(base)}"
    elif mode == 1:
        cand = base.rstrip(".!?") + "." + TAILS[seed % len(TAILS)]
    elif mode == 2:
        cand = swap_once(base, seed)
        if cand == base:
            cand = f"{OPENERS[(seed + 5) % len(OPENERS)]} {soften(base)}"
    else:
        mid = f"{OPENERS[(seed * 3) % len(OPENERS)]} {soften(swap_once(base, seed + 9))}".rstrip(".!?")
        cand = mid + "." + TAILS[(seed * 7) % len(TAILS)]
    return ensure_sentence(cand)


def expand_list(phrases: list[str], factor: float = 2.0) -> list[str]:
    target = int(len(phrases) * factor)
    out = list(phrases)
    seen = set(phrases)
    seed = 0
    guard = 0
    while len(out) < target and guard < target * 80:
        base = phrases[seed % len(phrases)]
        cand = one_variant(base, seed + len(phrases))
        seed += 1
        guard += 1
        if cand not in seen and len(cand) > 24:
            out.append(cand)
            seen.add(cand)
    return out[:target]


def format_list(name: str, phrases: list[str], comment: str | None) -> str:
    lines = []
    if comment:
        lines.append(f"    {comment}")
    lines.append(f"    val {name} = listOf(")
    for p in phrases:
        lines.append(f'        "{escape_kt(p)}",')
    lines.append("    )")
    return "\n".join(lines)


def main() -> None:
    text = PATH.read_text(encoding="utf-8")
    header_m = re.match(r"(?s)(package .*?object CommentaryPhrases \{\n)", text)
    footer_m = re.search(r"(?s)(\n    /\*\* Alias cho engine.*)", text)
    if not header_m or not footer_m:
        raise SystemExit("parse fail")
    header = header_m.group(1).rstrip("\n") + "\n\n"
    footer = footer_m.group(1)
    pattern = re.compile(
        r"(?s)(?:(    /\*\*.*?\*/)\n)?(    val ([A-Z_0-9]+) = listOf\()(.*?)(\n    \))",
    )
    parts = [header]
    total_a = total_b = 0
    for m in pattern.finditer(text):
        name = m.group(3)
        phrases = extract_strings(m.group(4))
        expanded = expand_list(phrases, 2.0)
        total_a += len(phrases)
        total_b += len(expanded)
        print(f"{name}: {len(phrases)} -> {len(expanded)}")
        parts.append(format_list(name, expanded, m.group(1)))
        parts.append("\n\n")
    new_text = "".join(parts).rstrip() + "\n" + footer.lstrip("\n")
    if not new_text.endswith("\n"):
        new_text += "\n"
    PATH.write_text(new_text, encoding="utf-8")
    print(f"TOTAL: {total_a} -> {total_b}")


if __name__ == "__main__":
    main()
