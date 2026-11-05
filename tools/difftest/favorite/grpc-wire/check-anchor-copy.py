#!/usr/bin/env python3
"""
**冻结锚副本一致性**核对 —— favorite 域对拍用。

用法：
    python3 check-anchor-copy.py <B 侧原件> <本仓副本>

为什么需要它（🔴 判据的输入必须是**被检查的对象**）：
    本仓持有的是 B 侧冻结锚的**副本**（B 侧仓只读 ⇒ 副本住在 C 侧）。
    ⇒ 若只核对「B 侧原件的 sha256 没变」，
      **改本仓副本里的冻结值**这件事不会被发现
      ⇒ ⇒ 探针会拿「被人改过的冻结值」判「三方一致」⇒ **假绿**。
    ⇒ ⇒ 这里**逐行**比对本仓副本与 B 侧原件的**数据行**（跳过注释行）
      ⇒ 副本的冻结列一旦脱节就退出。

退出码：0 = 一致；1 = 不一致。
"""

import sys


def data_lines(path):
    out = []
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        out.append(line)
    return out


def main():
    if len(sys.argv) != 3:
        print("用法：check-anchor-copy.py <B 侧原件> <本仓副本>", file=sys.stderr)
        return 2
    a_path, b_path = sys.argv[1], sys.argv[2]
    a = data_lines(a_path)
    b = data_lines(b_path)
    if a == b:
        print("    冻结锚副本一致（%d 条数据行逐行相同）" % len(a))
        return 0
    print("❌ 冻结锚副本与原件不一致 ⇒ 请重新拷贝（别改副本里的冻结值）")
    only_a = [x for x in a if x not in b]
    only_b = [x for x in b if x not in a]
    for x in only_a[:10]:
        print("   原件有、副本缺：%s" % x)
    for x in only_b[:10]:
        print("   副本有、原件缺：%s" % x)
    if not only_a and not only_b:
        print("   ⚠️ 行集合相同但**顺序不同** ⇒ 冻结锚的 case 顺序也是契约")
    return 1


if __name__ == "__main__":
    sys.exit(main())
