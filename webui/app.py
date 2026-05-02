import json
import asyncio
from multiprocessing import Process, Queue, Event
from pathlib import Path

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
import uvicorn
import webbrowser
import sys
from threading import Timer
import os

try:
    import tomllib
except ImportError:
    import tomli as tomllib

from core import run_server

def get_resource_path(relative_path: str):
    base_path = os.path.dirname(__file__)
    return Path(os.path.join(base_path, relative_path))

app = FastAPI()

CONFIG_PATH = get_resource_path("server-config.toml")
SCHEMA_PATH = get_resource_path("config_schema.json")

# 多进程状态管理
server_process = None
log_queue = Queue()
stop_event = Event()

class ConfigUpdate(BaseModel):
    config: dict


def load_toml():
    if CONFIG_PATH.exists():
        with CONFIG_PATH.open("rb") as f:
            return tomllib.load(f)
    return {}

def save_toml(config_dict):
    lines = []
    for key, value in config_dict.items():
        if isinstance(value, str):
            lines.append(f'{key} = "{value}"')
        elif isinstance(value, bool):
            lines.append(f"{key} = {'true' if value else 'false'}")
        else:
            lines.append(f"{key} = {value}")
    CONFIG_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")

@app.get("/")
def get_ui():
    index_path = get_resource_path("index.html")
    return HTMLResponse(index_path.read_text(encoding="utf-8"))

@app.get("/api/schema")
def get_schema():
    return json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))

@app.get("/api/config")
def get_config():
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    toml_data = load_toml()
    
    # 结合 Schema 填入默认值
    current_config = {}
    for item in schema:
        key = item["key"]
        current_config[key] = toml_data.get(key, item["default"])
    return current_config

@app.post("/api/config")
def update_config(data: ConfigUpdate):
    save_toml(data.config)
    return {"status": "success"}

@app.get("/api/status")
def get_status():
    is_running = server_process is not None and server_process.is_alive()
    return {"running": is_running}

@app.post("/api/start")
def start_server_api():
    global server_process, stop_event
    if server_process is None or not server_process.is_alive():
        stop_event.clear()
        server_process = Process(target=run_server, args=(log_queue, stop_event))
        server_process.start()
        return {"status": "started"}
    return {"status": "already running"}

@app.post("/api/stop")
def stop_server_api():
    global server_process, stop_event
    if server_process and server_process.is_alive():
        stop_event.set()
        server_process.join(timeout=3)
        if server_process.is_alive():
            server_process.terminate()
        return {"status": "stopped"}
    return {"status": "not running"}

@app.websocket("/ws/logs")
async def websocket_logs(websocket: WebSocket):
    await websocket.accept()
    try:
        while True:
            if not log_queue.empty():
                msg = log_queue.get()
                await websocket.send_text(msg)
            else:
                await asyncio.sleep(0.1)
    except WebSocketDisconnect:
        pass

def open_browser(port):
    webbrowser.open(f"http://127.0.0.1:{port}")

if __name__ == "__main__":
    host = "0.0.0.0"
    port = 8008

    if "--port" in sys.argv:
        try:
            port_index = sys.argv.index("--port") + 1
            port = int(sys.argv[port_index])
        except (IndexError, ValueError):
            print("Use default port 8008")

    Timer(1.5, open_browser, args=(port,)).start()

    print(f"Starting server: {host}:{port}")
    uvicorn.run(app, host=host, port=port)
