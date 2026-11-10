#!/usr/bin/env python3
"""「oracle 无效读数」档的**离线判据测试**（2026-11-10 补 · 域内 tool 自测）。

🔴 **为什么要有这个文件**（AGENTS §3.3「机制必须能真正触发」）：
    「oracle 无效读数」这一档的价值全在**退出码分档**上：
    · oracle 侧没答上来 ⇒ **EXIT=3**（不可判 ⇒ 需重跑）⇒ **不能**被记成「C 侧有差异」；
    · C 侧没答上来 ⇒ **EXIT=1**（真差异：基准存在，C 该答没答）。
    ⇒ 而这档判据**只在真机恰好出一次超时时才被观察到** ⇒ 不可复现 ⇒ 无法回归。
    ⇒ ⇒ 这里用**假的两侧**把各情形各造一遍 ⇒ 判据变成**可离线复现**的。

用法：
    python3 tools/difftest/task/test_invalid_tier.py
退出码：0 = 全部通过 · 1 = 有不通过。
"""

from __future__ import annotations

import http.server
import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import threading

HERE = pathlib.Path(__file__).resolve().parent

FAILED = 0


def check(label: str, got, want) -> None:
    global FAILED
    ok = got == want
    print(f"  {'✓' if ok else '!!'} {label}: got={got!r} want={want!r}")
    if not ok:
        FAILED += 1


def load(name: str):
    spec = importlib.util.spec_from_file_location(name, HERE / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


http_probe = load("http_probe")
grpc_probe = load("grpc_probe")


# ── 1. invalid_reason 的分类（HTTP 面）──────────────────────────────────────
def test_http_classification() -> None:
    print("== 1. HTTP 面 invalid_reason 分类 ==")

    def mk(status_line: str, body: bytes = b"", error: str = "") -> dict:
        return {"status_line": status_line,
                "headers": {"content-type": "", "x-content-type-options": "", "allow": ""},
                "body": body, "error": error}

    check("200 有效", http_probe.invalid_reason(mk("HTTP/1.1 200 ")), None)
    check("400 有效（本域错误路径是 400 + text/plain）",
          http_probe.invalid_reason(mk("HTTP/1.1 400 ")), None)
    check("404 有效", http_probe.invalid_reason(mk("HTTP/1.1 404 ")), None)
    check("405 有效", http_probe.invalid_reason(mk("HTTP/1.1 405 ")), None)
    check("504 无效（oracle 内部 hop 超时映射）",
          http_probe.invalid_reason(mk("HTTP/1.1 504 ")), "HTTP 504")
    check("500 无效", http_probe.invalid_reason(mk("HTTP/1.1 500 ")), "HTTP 500")
    check("连接失败 无效",
          http_probe.invalid_reason(mk("<CONNECT-FAILED:111>", error="refused")),
          "<CONNECT-FAILED:111>")
    check("无响应 无效", http_probe.invalid_reason(mk("<NO-RESPONSE>")), "<NO-RESPONSE>")


# ── 2. invalid_reason 的分类（gRPC 面）──────────────────────────────────────
def test_grpc_classification() -> None:
    print("== 2. gRPC 面 invalid_reason 分类 ==")
    check("OK 有效", grpc_probe.invalid_reason("status=OK|description=|task_count=2"), None)
    check("INVALID_ARGUMENT 有效（业务错误 · B 出处 TaskDomainService.java:90-93）",
          grpc_probe.invalid_reason("status=INVALID_ARGUMENT|description=user_id invalid"), None)
    check("UNKNOWN 有效（服务端真产生的 status · TaskServiceGrpcImpl.java:89-92）",
          grpc_probe.invalid_reason("status=UNKNOWN|description=boom"), None)
    check("INTERNAL 有效（服务端真产生的 status）",
          grpc_probe.invalid_reason("status=INTERNAL|description=boom"), None)
    check("UNAVAILABLE 无效",
          grpc_probe.invalid_reason("status=UNAVAILABLE|description="), "gRPC UNAVAILABLE")
    check("DEADLINE_EXCEEDED 无效",
          grpc_probe.invalid_reason("status=DEADLINE_EXCEEDED|description=timeout"),
          "gRPC DEADLINE_EXCEEDED")
    check("CANCELLED 无效",
          grpc_probe.invalid_reason("status=CANCELLED|description="), "gRPC CANCELLED")
    check("MISSING 无效", grpc_probe.invalid_reason("<MISSING>"), "<MISSING>")


# ── 3. compare 的退出码分档（用假服务器）────────────────────────────────────
class _Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):  # noqa: D102
        pass

    def do_POST(self):  # noqa: D102
        length = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(length)
        mode = self.server.mode  # type: ignore[attr-defined]
        if mode == "ok":
            body = b'{"tasks":[]}'
        elif mode == "server-error":
            body = b"boom\n"
        elif mode == "different":
            my_port = self.server.server_address[1]  # type: ignore[attr-defined]
            body = b'{"tasks":["x"]}' if my_port == self.server.b_port else b'{"tasks":[]}'
        else:
            body = b""
        self.send_response(200 if mode in ("ok", "different") else 504)
        self.send_header("Content-Type",
                         "application/json;charset=utf-8" if mode != "server-error"
                         else "text/plain;charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def _serve(mode: str, b_port: int | None = None):
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    srv.mode = mode  # type: ignore[attr-defined]
    srv.b_port = b_port if b_port is not None else srv.server_address[1]  # type: ignore[attr-defined]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def _serve_pair(mode_b: str, mode_c: str):
    b = _serve(mode_b)
    c = _serve(mode_c, b_port=b.server_address[1])
    return b, c


def _run_compare(b_port: int, c_port: int, cases: pathlib.Path,
                 expected: pathlib.Path | None = None) -> tuple[int, str]:
    cmd = [sys.executable, str(HERE / "http_probe.py"), "compare",
           "--b-port", str(b_port), "--c-port", str(c_port), "--cases", str(cases)]
    if expected:
        cmd += ["--expected", str(expected)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr


def test_exit_code_tiers() -> None:
    print("== 3. compare 的退出码分档 ==")
    tmp = pathlib.Path(tempfile.mkdtemp())
    cases = tmp / "cases.txt"
    cases.write_text('c1\tPOST\t/taskcenter/v1/task/get\tapplication/json\t{"id":"1001"}\n',
                     encoding="utf-8")

    print("  -- A. oracle 侧 5xx ＋ C 侧正常 ⇒ EXIT=3（不可判，**不是**真差异）")
    b, c = _serve_pair("server-error", "ok")
    rc, out = _run_compare(b.server_address[1], c.server_address[1], cases)
    check("退出码", rc, 3)
    check("输出含「不可判」", "不可判" in out, True)
    check("输出含「真差异 0 条」", "真差异 0 条" in out, True)
    b.shutdown(); c.shutdown()

    print("  -- B. oracle 侧正常 ＋ C 侧 5xx ⇒ EXIT=1（真差异：基准存在，C 该答没答）")
    b, c = _serve_pair("ok", "server-error")
    rc, out = _run_compare(b.server_address[1], c.server_address[1], cases)
    check("退出码", rc, 1)
    check("输出含「真差异 1 条」", "真差异 1 条" in out, True)
    b.shutdown(); c.shutdown()

    print("  -- C. 两侧都正常且逐字节相同 ⇒ EXIT=0")
    b, c = _serve_pair("ok", "ok")
    rc, _ = _run_compare(b.server_address[1], c.server_address[1], cases)
    check("退出码", rc, 0)
    b.shutdown(); c.shutdown()

    print("  -- D. 两侧都正常但内容不同 ⇒ EXIT=1")
    b, c = _serve_pair("different", "different")
    rc, _ = _run_compare(b.server_address[1], c.server_address[1], cases)
    check("退出码", rc, 1)
    b.shutdown(); c.shutdown()

    print("  -- E. oracle 侧 5xx ＋ C 侧与冻结锚不符 ⇒ EXIT=1（不可判**不能**吃掉真回归）")
    anchor = tmp / "expected.txt"
    anchor.write_text("c1\tHTTP|HTTP/1.1 200 |application/json;charset=utf-8|||\n",
                      encoding="utf-8")
    b, c = _serve_pair("server-error", "different")
    rc, out = _run_compare(b.server_address[1], c.server_address[1], cases, anchor)
    check("退出码", rc, 1)
    check("输出提到冻结锚不一致", "冻结锚不一致" in out, True)
    b.shutdown(); c.shutdown()

    print("  -- F. FREEZE ＋ oracle 侧 5xx ⇒ 拒绝写锚（部分锚 = 静默削弱的锚）")
    freeze_target = tmp / "frozen.txt"
    freeze_target.write_text("KEEP-ME\n", encoding="utf-8")
    fb, fc = _serve_pair("server-error", "ok")
    cmd = [sys.executable, str(HERE / "http_probe.py"), "compare",
           "--b-port", str(fb.server_address[1]), "--c-port", str(fc.server_address[1]),
           "--cases", str(cases), "--freeze", str(freeze_target)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    check("退出码", r.returncode, 3)
    check("输出含「拒绝冻结」", "拒绝冻结" in (r.stdout + r.stderr), True)
    check("旧锚未被改写",
          freeze_target.read_text(encoding="utf-8"), "KEEP-ME\n")
    fb.shutdown(); fb.server_close(); fc.shutdown(); fc.server_close()

    print("  -- G. 已登记差异用例 ＋ oracle 侧 5xx ⇒ 仍按**登记形状**判 C 侧（**不能拿冻结锚判**）")
    # 已知差异用例：C 侧读数**本来**就与冻结锚不同 ⇒ 若 oracle 无效时仍拿锚判 C
    # ⇒ ⇒ **必然假红**（本轮实测发现的真 bug）⇒ ⇒ 必须改用「登记形状」当基准。
    known_cases = tmp / "known-cases.txt"
    known_readings = tmp / "known-readings.txt"
    known_cases.write_text("c1\t已登记的表达层差异\n", encoding="utf-8")
    gb, gc = _serve_pair("server-error", "ok")
    # 🔴 **登记形状必须从活体服务器反推**，不能手抄：
    #    `render()` 的输出含**状态行的 reason phrase**（`HTTP/1.1 200 OK`）
    #    ⇒ 手抄很容易写成 `HTTP/1.1 200 `（少一个 reason phrase）⇒ 必然对不上
    #    ⇒ ⇒ 一旦对不上，**失败原因会变成「形状变了」（真差异）而不是「不可判」**
    #    ⇒ ⇒ 夹具与被测逻辑**共用同一个 render()** ⇒ 格式变了自动跟随。
    live = http_probe.probe(gc.server_address[1], "POST", "/taskcenter/v1/task/get",
                             "application/json", '{"id":"1001"}')
    real_shape = http_probe.render(live)
    known_readings.write_text(f"c1\t{real_shape}\n", encoding="utf-8")
    # 冻结锚 = 一个**故意不同**的形状（模拟「C 与 oracle 本来就有已登记差异」）
    anchor2 = tmp / "expected.txt"
    anchor2.write_text(
        "c1\tHTTP|HTTP/1.1 200 OK|application/json;charset=utf-8|||7b7d\n", encoding="utf-8")
    cmd = [sys.executable, str(HERE / "http_probe.py"), "compare",
           "--b-port", str(gb.server_address[1]), "--c-port", str(gc.server_address[1]),
           "--cases", str(cases), "--expected", str(anchor2),
           "--known-cases", str(known_cases), "--known-readings", str(known_readings)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    out = r.stdout + r.stderr
    check("退出码（不可判 ⇒ 3，不是假红 1）", r.returncode, 3)
    check("未误报「与冻结锚不一致」", "冻结锚不一致" not in out, True)
    check("未误报「形状变了」", "形状变了" not in out, True)
    check("确实记成不可判", "不可判 1 条" in out, True)

    print("  -- G2. 已登记差异用例 ＋ oracle 侧 5xx ＋ C 侧形状**真的变了** ⇒ EXIT=1（真差异）")
    # 🔴 反向用例：**不可判不能吃掉真回归** —— oracle 无效时，C 侧若与登记形状不符
    #    ⇒ 仍然必须报真差异（EXIT=1），而不是被「不可判」降级成 EXIT=3。
    wrong_readings = tmp / "known-readings-wrong.txt"
    wrong_readings.write_text(
        "c1\tHTTP|HTTP/1.1 200 OK|application/json;charset=utf-8|||7b7d\n", encoding="utf-8")
    cmd = [sys.executable, str(HERE / "http_probe.py"), "compare",
           "--b-port", str(gb.server_address[1]), "--c-port", str(gc.server_address[1]),
           "--cases", str(cases), "--expected", str(anchor2),
           "--known-cases", str(known_cases), "--known-readings", str(wrong_readings)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    out = r.stdout + r.stderr
    check("退出码（真差异优先于不可判 ⇒ 1）", r.returncode, 1)
    check("报出了「形状变了」", "形状变了" in out, True)
    gb.shutdown(); gb.server_close(); gc.shutdown(); gc.server_close()


def main() -> int:
    test_http_classification()
    test_grpc_classification()
    test_exit_code_tiers()
    print(f"\n{'✅ 全部通过' if FAILED == 0 else f'❌ {FAILED} 条不通过'}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
