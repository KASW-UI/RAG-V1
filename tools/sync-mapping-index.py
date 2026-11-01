#!/usr/bin/env python3
"""同步 docs/Mapping_Table/_INDEX.md 的覆盖度数字（A 列覆盖 → B 列覆盖）。

数字**从文件系统实测**（复用 check-mapping-coverage 的函数），不手填。
识别特征：数据行 = 6 格，且第 4/5 格含 `/`（覆盖度）、第 6 格是整数（映射行）
⇒ §二「覆盖度表」与 §三「落点缺口表」两张表都能命中；其余表不动。

⚠️ **按 section 分派第 2 格**（2026-11-02 修）：
  §二「覆盖度表」第 2 格 = **文档列** ⇒ 允许脚本写入域文档清单；
  §三「落点缺口表」第 2 格 = **缺口描述列** ⇒ **必须原样保留**。
  D16 落地时本脚本对两张表一视同仁地写 `doc_cells` ⇒ **21 条缺口描述被覆盖成文件列表**
  （且 §三 表头仍是 2 列「域 | 缺口」而数据行 6 格 ⇒ 表格形状坏）。
  ⇒ 修法：**按当前 `## ` 节标题分派** + **§三 表头补齐 6 格形状**。

用法：python3 tools/sync-mapping-index.py [--apply]
"""
import os
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from importlib import import_module
_cov = import_module("check-mapping-coverage")

MT = ROOT / "docs/Mapping_Table"
IDX = MT / "_INDEX.md"


def actual():
    """域 → (行数, C分子, C分母, B分子, B分母, 映射行)"""
    out = {}
    for dom, (v_rel, rag_rel, docs) in _cov.DOMAINS.items():
        doc_list = (docs,) if isinstance(docs, str) else tuple(docs)
        text = "\n".join((MT / d).read_text(encoding="utf-8") for d in doc_list)
        js = _cov.java_files(v_rel)
        c_cov, _ = _cov.coverage(text, js)
        lines = sum(len((MT / d).read_text(encoding="utf-8").splitlines()) for d in doc_list)
        out[dom] = [lines, c_cov, len(js), None, None, _cov.count_map_rows(text)]
    _p, _s, _u, bundle = _cov.check_b_column()
    bcov = _cov.b_coverage_by_domain(bundle)
    for dom, (cov, tot, _miss) in bcov.items():
        if dom in out:
            l, cc, ct, _b1, _b2, mr = out[dom]
            out[dom] = [l, cc, ct, cov, tot, mr]
    return out


def is_cover_row(cells):
    if len(cells) != 6:
        return False
    if not cells[0].startswith("**"):
        return False
    if "/" not in cells[3] or "/" not in cells[4]:
        return False
    if not re.fullmatch(r"[\d,\s*]+", cells[5].strip()):
        return False
    return True


def main():
    apply_changes = "--apply" in sys.argv
    t = IDX.read_text(encoding="utf-8")
    lines = t.splitlines()
    act = actual()

    doc_cells = {
        "users": "[`_shared.md`](users/_shared.md) · [users-common.md](users/users-common.md) · [user/user.md](users/user/user.md) · [admin/admin.md](users/admin/admin.md)",
        "common": "[common.md](common.md)",
        "article": "[article/article.md](article/article.md)",
        "comment": "[comment/comment.md](comment/comment.md)",
        "favorite": "[favorite/favorite.md](favorite/favorite.md)",
        "follow": "[follow/follow.md](follow/follow.md)",
        "hot": "[hot/hot.md](hot/hot.md)",
        "like": "[like/like.md](like/like.md)",
        "message": "[message/message.md](message/message.md)",
        "task": "[task/task.md](task/task.md)",
    }

    out, replaced = [], set()
    section = ""          # "二" = 覆盖度表 · "三" = 落点缺口表
    expect_sep = False    # §三 表头已改写 ⇒ 下一行若是分隔行也需补齐 6 格
    for ln in lines:
        s = ln.strip()
        m_sec = re.match(r"^##\s*([二三四五六七八九十]+)、", ln)
        if m_sec:
            section = m_sec.group(1)
            expect_sep = False
        if s.startswith("|") and s.endswith("|"):
            cells = [c.strip() for c in s.strip("|").split("|")]
            # §三 子表：表头「| 域 | 缺口 |」补齐成 6 格（数据行本来就是 6 格）
            if section == "三" and len(cells) == 2 and cells[0] == "域" and cells[1] == "缺口":
                out.append("| 域 | 缺口 | 行数 | C 列覆盖 | B 列覆盖 | 映射行 |")
                expect_sep = True
                continue
            if expect_sep and all(set(c) <= set("-: ") and c for c in cells):
                out.append("|---|---|---:|---|---|---:|")
                expect_sep = False
                continue
            expect_sep = False
            if is_cover_row(cells):
                dom = cells[0].strip("*")
                if dom == "合计":
                    tl = sum(v[0] for v in act.values())
                    tc = sum(v[1] for v in act.values())
                    tct = sum(v[2] for v in act.values())
                    tb = sum(v[3] for v in act.values())
                    tbt = sum(v[4] for v in act.values())
                    tm = sum(v[5] for v in act.values())
                    out.append(f"| **合计** |  | **{tl:,}** | **{tc} / {tct} = "
                               f"{tc*100//tct}%** | **{tb} / {tbt} = {tb*100//tbt}%** | {tm} |")
                    continue
                if dom in act:
                    l, cc, ct, bc, bt, mr = act[dom]
                    # §二 第 2 格 = 文档列（可写）；§三 第 2 格 = 缺口描述（**原样保留**）
                    if section == "二":
                        col2 = doc_cells.get(dom, cells[1])
                    else:
                        col2 = cells[1]
                    out.append(f"| **{dom}** | {col2} | {l:,} | "
                               f"**{cc} / {ct} = {cc*100//ct if ct else 0}%** | "
                               f"**{bc} / {bt} = {bc*100//bt if bt else 0}%** | {mr} |")
                    replaced.add(dom)
                    continue
        out.append(ln)

    new = "\n".join(out) + ("\n" if t.endswith("\n") else "")
    # 表头：A 列覆盖 → B 列覆盖
    new = new.replace("| 域 | 文档 | 行数 | C 列覆盖 | A 列覆盖 | 映射行 |",
                      "| 域 | 文档 | 行数 | C 列覆盖 | B 列覆盖 | 映射行 |")
    new = new.replace("| 域 | 缺口 | 行数 | C 列覆盖 | A 列覆盖 | 映射行 |",
                      "| 域 | 缺口 | 行数 | C 列覆盖 | B 列覆盖 | 映射行 |")

    missing = set(act) - replaced
    if missing:
        print(f"⚠️ 未替换的域行：{sorted(missing)}")

    if apply_changes:
        tmp = IDX.with_suffix(".md.tmp")
        tmp.write_text(new, encoding="utf-8")
        os.replace(tmp, IDX)
        print(f"✅ 已写入 {IDX}")
    else:
        print("DRY-RUN —— 加 --apply 落地")
    for dom in sorted(act):
        v = act[dom]
        print(f"  {dom:<10} 行数 {v[0]:>5} · C {v[1]}/{v[2]} · B {v[3]}/{v[4]} · 映射行 {v[5]}")


if __name__ == "__main__":
    main()
