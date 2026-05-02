import math
import socket
import threading
import time
from pathlib import Path
import pydirectinput
from pynput import keyboard

try:
    import tomllib
except ImportError:
    import tomli as tomllib

def run_server(log_queue, stop_event):
    CONFIG_PATH = Path("server-config.toml")
    
    # 动态加载配置项以脱离硬编码依赖
    def load_config():
        config = {}
        if CONFIG_PATH.exists():
            with CONFIG_PATH.open("rb") as config_file:
                config.update(tomllib.load(config_file))
        return config

    def save_config(config):
        lines = []
        for key, value in config.items():
            if isinstance(value, str):
                encoded = value.replace("\\", "\\\\").replace('"', '\\"')
                lines.append(f'{key} = "{encoded}"')
            elif isinstance(value, bool):
                lines.append(f"{key} = {'true' if value else 'false'}")
            else:
                lines.append(f"{key} = {value}")
        CONFIG_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def log(message):
        formatted = f"[{time.strftime('%H:%M:%S')}] {message}"
        if log_queue:
            log_queue.put(formatted)
        print(formatted)

    config = load_config()

    # 安全获取配置（带默认值回退）
    UDP_IP = config.get("udp_ip", "0.0.0.0")
    UDP_PORT = int(config.get("udp_port", 5005))
    TCP_IP = config.get("tcp_ip", UDP_IP)
    TCP_PORT = int(config.get("tcp_port", UDP_PORT))
    SENSITIVITY = float(config.get("sensitivity", 1.0))
    SCREEN_WIDTH = int(config.get("screen_width", 2880))
    ZOOM_LEVEL = int(config.get("zoom_level", 2))
    ANGLE_DEAD_ZONE = float(config.get("angle_dead_zone", 0.05))
    INTERPOLATION_SLEEP = float(config.get("interpolation_sleep", 0.005))
    ACCEL_INTERPOLATION_STEPS = max(1, int(config.get("accel_interpolation_steps", 3)))
    ACCEL_FILTER_ALPHA = float(config.get("accel_filter_alpha", 0.35))
    ACCEL_TARGET_HYSTERESIS_PX = max(
        ACCEL_INTERPOLATION_STEPS,
        int(config.get("accel_target_hysteresis_steps", 1)) * ACCEL_INTERPOLATION_STEPS
    )

    ACCEL_COEFFICIENT = SCREEN_WIDTH / 9.8
    MIDPOINT = SCREEN_WIDTH // ZOOM_LEVEL // 2

    state = {
        "is_controlling": True,
        "accel_zero_g": float(config.get("accel_zero_g", 0.0)),
        "accel_filtered_value": 0.0,
        "gyro_remainder": 0.0,
        "move_target_x": MIDPOINT,
        "last_queued_target_x": MIDPOINT,
        "move_steps_remaining": 0,
        "current_keys_state": [0] * 6
    }
    
    keys_table = ["shift", "a", "s", "d", "f", "space"]
    pydirectinput.PAUSE = 0
    move_target_lock = threading.Lock()
    move_target_event = threading.Event()

    def set_accel_zero(new_zero):
        state["accel_zero_g"] = float(new_zero)
        state["accel_filtered_value"] = 0.0
        config["accel_zero_g"] = state["accel_zero_g"]
        save_config(config)
        log(f"Saved accelerometer zero g = {state['accel_zero_g']:.5f}")

    def on_press(key):
        try:
            if key == keyboard.Key.backspace:
                state["is_controlling"] = not state["is_controlling"]
                log(f"Control {'enabled' if state['is_controlling'] else 'disabled'}")
        except AttributeError:
            pass

    def quantize_accel_target(raw_target_x):
        delta_from_midpoint = raw_target_x - MIDPOINT
        quantized_delta = round(delta_from_midpoint / ACCEL_INTERPOLATION_STEPS) * ACCEL_INTERPOLATION_STEPS
        return MIDPOINT + quantized_delta

    def queue_accel_target(raw_value):
        adjusted_value = raw_value - state["accel_zero_g"]
        state["accel_filtered_value"] += (adjusted_value - state["accel_filtered_value"]) * ACCEL_FILTER_ALPHA
        filtered_value = 0.0 if abs(state["accel_filtered_value"]) < ANGLE_DEAD_ZONE else state["accel_filtered_value"]
        raw_target_x = math.floor(filtered_value * ACCEL_COEFFICIENT + MIDPOINT)
        target_x = quantize_accel_target(raw_target_x)
        if abs(target_x - MIDPOINT) <= ACCEL_TARGET_HYSTERESIS_PX:
            target_x = MIDPOINT
            
        with move_target_lock:
            if target_x == state["last_queued_target_x"] and state["move_steps_remaining"] > 0:
                return
            if abs(target_x - state["move_target_x"]) < ACCEL_TARGET_HYSTERESIS_PX and target_x != MIDPOINT:
                return
            state["move_target_x"] = target_x
            state["last_queued_target_x"] = target_x
            state["move_steps_remaining"] = ACCEL_INTERPOLATION_STEPS
        move_target_event.set()

    def move_to_midpoint():
        with move_target_lock:
            state["move_target_x"] = MIDPOINT
            state["last_queued_target_x"] = MIDPOINT
            state["move_steps_remaining"] = ACCEL_INTERPOLATION_STEPS
        move_target_event.set()

    def apply_gyro(raw_value):
        adjusted_value = 0.0 if abs(raw_value) < ANGLE_DEAD_ZONE else raw_value
        total_delta = (-adjusted_value * SENSITIVITY / 2.0) + state["gyro_remainder"]
        hid_delta = math.trunc(total_delta)
        state["gyro_remainder"] = total_delta - hid_delta 
        if hid_delta != 0:
            pydirectinput.moveRel(xOffset=hid_delta, yOffset=0, relative=True)

    def move_worker():
        while not stop_event.is_set():
            move_target_event.wait(0.05)
            if stop_event.is_set():
                break
            if not move_target_event.is_set():
                continue

            with move_target_lock:
                target_x = state["move_target_x"]
                steps_remaining = state["move_steps_remaining"]

            current_x = pydirectinput.position()[0]
            delta_x = int(round(target_x - current_x))
            if delta_x == 0 or steps_remaining <= 0:
                with move_target_lock:
                    if state["move_target_x"] == target_x:
                        state["move_steps_remaining"] = 0
                        move_target_event.clear()
                continue

            step_delta = int(round(delta_x / steps_remaining))
            if step_delta == 0:
                step_delta = 1 if delta_x > 0 else -1
            pydirectinput.moveRel(xOffset=step_delta, yOffset=0, relative=True)

            with move_target_lock:
                if state["move_target_x"] == target_x:
                    state["move_steps_remaining"] = max(0, state["move_steps_remaining"] - 1)
                    if state["move_steps_remaining"] == 0:
                        move_target_event.clear()
            time.sleep(INTERPOLATION_SLEEP)

    def handle_message(message):
        raw_msg = message.decode().split("|", 1)
        if len(raw_msg) != 2: return
        key_type, key_para = raw_msg[0], raw_msg[1]

        if key_type == "AZ": set_accel_zero(float(key_para)); return
        if key_type == "P": pydirectinput.press("esc"); log("Sent Esc pause toggle"); return
        if not state["is_controlling"]: return

        if key_type == "RESET": move_to_midpoint()
        elif key_type in ("A", "G"): queue_accel_target(float(key_para))
        elif key_type == "M": apply_gyro(float(key_para))
        elif key_type == "K":
            for i, k_state in enumerate(str(key_para)):
                if i >= len(state["current_keys_state"]): break
                if k_state == "1" and state["current_keys_state"][i] == 0:
                    pydirectinput.keyDown(keys_table[i])
                    state["current_keys_state"][i] = 1
                elif k_state == "0" and state["current_keys_state"][i] == 1:
                    pydirectinput.keyUp(keys_table[i])
                    state["current_keys_state"][i] = 0

    def tcp_client_worker(client_socket, client_addr):
        log(f"TCP client connected {client_addr[0]}:{client_addr[1]}")
        buffer = ""
        try:
            client_socket.settimeout(0.1)
            while not stop_event.is_set():
                try:
                    chunk = client_socket.recv(1024)
                    if not chunk: break
                    buffer += chunk.decode()
                    while "\n" in buffer:
                        line, buffer = buffer.split("\n", 1)
                        if line.strip(): handle_message(line.encode())
                except socket.timeout: continue
        except OSError: pass
        finally:
            client_socket.close()
            log(f"TCP client disconnected {client_addr[0]}:{client_addr[1]}")

    def tcp_server_worker(server_socket):
        log(f"TCP receiver listening on {TCP_IP}:{TCP_PORT}")
        server_socket.settimeout(0.1)
        while not stop_event.is_set():
            try:
                client_socket, client_addr = server_socket.accept()
                threading.Thread(target=tcp_client_worker, args=(client_socket, client_addr), daemon=True).start()
            except socket.timeout: continue
            except OSError: break

    # 启动监听和线程
    listener = keyboard.Listener(on_press=on_press)
    listener.start()
    
    move_thread = threading.Thread(target=move_worker, daemon=True)
    move_thread.start()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((UDP_IP, UDP_PORT))
    sock.settimeout(0.1)

    tcp_server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    tcp_server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    tcp_server_socket.bind((TCP_IP, TCP_PORT))
    tcp_server_socket.listen()

    tcp_server_thread = threading.Thread(target=tcp_server_worker, args=(tcp_server_socket,), daemon=True)
    tcp_server_thread.start()

    log("Server Core Started")

    try:
        while not stop_event.is_set():
            try:
                data, addr = sock.recvfrom(1024)
                handle_message(data)
            except socket.timeout:
                continue
    finally:
        move_target_event.set()
        tcp_server_socket.close()
        listener.stop()
        sock.close()
        log("Server Core Stopped")
