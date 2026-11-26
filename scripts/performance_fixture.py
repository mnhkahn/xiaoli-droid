#!/usr/bin/env python3
"""Private localhost rendering fixture; no real model responses or user data."""
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs

MESSAGES = []
for index in range(80):
    text = f"第 {index + 1} 条测试消息\n\n这是一段用于滚动性能验证的中文文本。**文字选择、Markdown 和稳定滚动**都应保持流畅。\n\n- 保留当前阅读位置\n- 附件不会打断滚动\n- 新回复按需跟随"
    if index % 8 == 0:
        text += "\n\n| 项目 | 状态 |\n|---|---|\n| 消息 | 完成 |\n| 附件 | 完成 |\n\n```kotlin\nval smooth = true\nprintln(smooth)\n```"
    if index % 13 == 0:
        text += "\n\n$$\nx^2 + y^2 = z^2\n$$"
    MESSAGES.append({"id": f"perf-{index}", "role": "user" if index % 2 else "assistant", "text": text, "status": "complete"})

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.headers.get("Authorization") != "Bearer performance-fixture":
            self.send_error(401)
            return
        parsed = urlsplit(self.path)
        if parsed.path == "/mobile/v1/sessions":
            self.reply({"sessions": [{"id": "performance", "title": "滚动验收"}]})
            return
        before = int(parse_qs(parsed.query).get("before", [len(MESSAGES)])[0])
        start = max(0, before - 50)
        snapshot = {"id": "performance", "title": "滚动验收", "messages": MESSAGES[start:before], "pending": [], "busy": False, "revision": 1, "before": start}
        if parsed.path.endswith("/events"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            try:
                data = json.dumps({"id": 1, "type": "snapshot", "data": snapshot}, ensure_ascii=False)
                self.wfile.write(f"data: {data}\n\n".encode())
                self.wfile.flush()
                for _ in range(300):
                    time.sleep(2)
                    self.wfile.write(b": heartbeat\n\n")
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        self.reply(snapshot)

    def reply(self, value):
        data = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 18080), Handler).serve_forever()
