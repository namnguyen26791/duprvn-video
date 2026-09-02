# -*- coding: utf-8 -*-
"""Rebuild CommentaryPhrases.kt banks at 3x from the first third (originals)."""
from __future__ import annotations

import re
from pathlib import Path

PATH = Path(
    r"c:\Users\nam.nguyen19\Documents\prj\pickleball\vdpr\duprvn-video"
    r"\app\src\main\java\com\pickleball\video\commentary\CommentaryPhrases.kt"
)

OPENERS = [
    "Nhìn này,",
    "Xin phép,",
    "Đúng rồi,",
    "Thật đáng xem,",
    "Giữ mắt trên sân,",
    "Không khí vừa nhảy một nấc,",
    "Loa bình luận xin phép nóng,",
    "Khán đài ơi,",
    "Một nhịp nữa,",
    "Cảm xúc đang cao,",
    "Đúng chất giải đấu,",
    "Vâng,",
    "Ôi giời,",
    "Xin giữ chỗ ngồi,",
    "Một khoảnh khắc đáng tiền,",
    "Trời đất ơi,",
    "Nghe này quý vị,",
    "Sân đang rất đáng theo dõi,",
    "Bản lĩnh lên tiếng,",
    "Nhanh thôi,",
]

TAILS = [
    " Xin giữ tinh thần cổ vũ đến cùng.",
    " Đừng chỉnh kênh nhé quý vị.",
    " Cảm xúc sân đang rất đáng theo dõi.",
    " Bản lĩnh sẽ lên tiếng ngay thôi.",
    " Hãy cùng thở chung một nhịp với sân.",
    " Đây mới là chất lượng trận đấu.",
    " Khán đài đang nóng dần từng giây.",
    " Loa bình luận cũng muốn nói to hơn.",
    " Một pha nữa có thể đổi cả cục diện.",
    " Xin vỗ tay tiếp sức cho cả hai phía.",
    " Trận đấu vẫn còn rất nhiều chuyện để kể.",
    " Đừng bỏ lỡ giây nào lúc này.",
    " Áp lực và niềm vui đang trộn đều trên sân.",
    " Tinh thần thể thao đang lên tiếng đẹp mắt.",
    " Quá đáng xem quý vị ạ.",
    " Hãy ở lại đến phút cuối nhé.",
    " Tim khán đài cũng đập theo từng nhịp.",
    " Câu chuyện trên sân vẫn chưa dừng lại.",
    " Phong độ lúc này rất đáng ghi nhận.",
    " Xin cảm ơn không khí tuyệt đẹp trên sân.",
]

SWAPS = [
    ("quý vị ơi", "mọi người ơi"),
    ("quý vị ạ", "mọi người ạ"),
    ("quý vị", "anh chị em"),
    ("trời ơi", "ôi giời"),
    ("bảng điểm", "bảng tỉ số"),
    ("hiện tại", "lúc này"),
    ("tỉ số", "điểm số"),
    ("khán đài", "khán giả"),
    ("chiến thắng", "thắng lợi"),
    ("kết thúc", "khép sổ"),
    ("trận đấu", "cuộc chơi"),
    ("gay cấn", "căng thẳng"),
    ("bản lĩnh", "đẳng cấp"),
    ("cảm xúc", "không khí"),
    ("đáng xem", "đáng tiền"),
    ("sạch sẽ", "gọn gàng"),
    ("vỗ tay", "reo hò"),
    ("nín thở", "giữ hơi"),
    ("xin chào", "kính chào"),
    ("quá hay", "quá đỉnh"),
    ("đứng dậy", "đứng cả lên"),
    ("mỉm cười", "cười nhẹ"),
]


def extract_strings(block: str) -> list[str]:
    return re.findall(r'"((?:\\.|[^"\\])*)"', block)


def escape_kt(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"')


def ensure_sentence(s: str) -> str:
    s = re.sub(r"\s{2,}", " ", s).strip()
    s = s.replace("..", ".")
    if s and s[-1] not in ".!?":
        s += "."
    return s


def soften(s: str) -> str:
    if not s or s.startswith("{"):
        return s
    return s[0].lower() + s[1:] if s[0].isupper() else s


def swap_once(s: str, idx: int) -> str:
    out = s
    applied = 0
    for i, (a, b) in enumerate(SWAPS):
        if (i + idx) % 3 != 0:
            continue
        if a in out:
            out = out.replace(a, b, 1)
            applied += 1
            if applied >= 2:
                break
    return out


def one_variant(base: str, seed: int) -> str:
    mode = seed % 4
    if mode == 0:
        op = OPENERS[seed % len(OPENERS)]
        cand = f"{op} {soften(base)}"
    elif mode == 1:
        core = base.rstrip(".!?")
        cand = core + "." + TAILS[seed % len(TAILS)]
    elif mode == 2:
        cand = swap_once(base, seed)
        if cand == base:
            cand = swap_once(base, seed + 7)
        if cand == base:
            op = OPENERS[(seed + 3) % len(OPENERS)]
            cand = f"{op} {soften(base)}"
    else:
        op = OPENERS[(seed * 3) % len(OPENERS)]
        core = swap_once(base, seed + 11)
        mid = f"{op} {soften(core)}".rstrip(".!?")
        cand = mid + "." + TAILS[(seed * 5) % len(TAILS)]
    return ensure_sentence(cand)


def triple_list(phrases: list[str]) -> list[str]:
    target = len(phrases) * 3
    out = list(phrases)
    seen = set(phrases)
    seed = 0
    guard = 0
    while len(out) < target and guard < target * 100:
        base = phrases[(len(out) - len(phrases)) % len(phrases)]
        cand = one_variant(base, seed)
        seed += 1
        guard += 1
        if cand not in seen and len(cand) > 24:
            out.append(cand)
            seen.add(cand)
    if len(out) < target:
        raise SystemExit(f"only got {len(out)}/{target}")
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
    if not header_m:
        raise SystemExit("header not found")
    header = header_m.group(1).rstrip("\n") + "\n\n"

    footer_m = re.search(r"(?s)(\n    /\*\* Alias cho engine.*)", text)
    if not footer_m:
        raise SystemExit("footer not found")
    footer = footer_m.group(1)

    pattern = re.compile(
        r"(?s)(?:(    /\*\*.*?\*/)\n)?(    val ([A-Z_0-9]+) = listOf\()(.*?)(\n    \))",
    )
    banks = list(pattern.finditer(text))
    if not banks:
        raise SystemExit("no banks found")

    parts = [header]
    stats = []
    for m in banks:
        comment = m.group(1)
        name = m.group(3)
        phrases = extract_strings(m.group(4))
        if not phrases:
            raise SystemExit(f"empty {name}")
        if len(phrases) % 3 == 0:
            originals = phrases[: len(phrases) // 3]
        else:
            originals = phrases
        tripled = triple_list(originals)
        stats.append((name, len(originals), len(tripled)))
        parts.append(format_list(name, tripled, comment))
        parts.append("\n\n")

    new_text = "".join(parts).rstrip() + "\n" + footer.lstrip("\n")
    if not new_text.endswith("\n"):
        new_text += "\n"
    PATH.write_text(new_text, encoding="utf-8")

    for name, a, b in stats:
        print(f"{name}: {a} -> {b}")
    print(f"TOTAL: {sum(a for _, a, _ in stats)} -> {sum(b for _, _, b in stats)}")

    # missing period before tail
    glued = len(re.findall(r'[a-záàảãạăắằẳẵặâấầẩẫậéèẻẽẹêếềểễệíìỉĩịóòỏõọôốồổỗộơớờởỡợúùủũụưứừửữựýỳỷỹỵ]Đừng chỉnh', new_text, re.I))
    print("glued_before_Dung:", glued)


if __name__ == "__main__":
    main()
