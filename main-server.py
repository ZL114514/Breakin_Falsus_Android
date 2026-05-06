import math
import socket
import threading
import time
from ctypes import Structure, Union, byref, c_long, c_ulong, c_void_p, sizeof, windll
from pathlib import Path

import pydirectinput
from pynput import keyboard

try:
    import tomllib
except ImportError:
    import tomli as tomllib


CONFIG_PATH = Path("server-config.toml")
DEFAULT_CONFIG = {
    "udp_ip": "0.0.0.0",
    "udp_port": 5005,
    "tcp_ip": "0.0.0.0",
    "tcp_port": 5005,
    "sensitivity": 1.0,
    "screen_width": 2880,
    "zoom_level": 2,
    "angle_dead_zone": 0.05,
    "interpolation_sleep": 0.005,
    "accel_interpolation_steps": 3,
    "accel_zero_g": 0.0,
    "accel_filter_alpha": 0.35,
    "accel_target_hysteresis_steps": 1,
}


def load_config():
    config = dict(DEFAULT_CONFIG)
    if CONFIG_PATH.exists():
        with CONFIG_PATH.open("rb") as config_file:
            loaded = tomllib.load(config_file)
        config.update(loaded)
    return config


def save_config(config):
    ordered_keys = list(DEFAULT_CONFIG.keys())
    ordered_keys.extend(key for key in config.keys() if key not in ordered_keys)
    lines = []
    for key in ordered_keys:
        value = config[key]
        if isinstance(value, str):
            encoded = value.replace("\\", "\\\\").replace('"', '\\"')
            lines.append(f'{key} = "{encoded}"')
        elif isinstance(value, bool):
            lines.append(f"{key} = {'true' if value else 'false'}")
        else:
            lines.append(f"{key} = {value}")
    CONFIG_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")


config = load_config()
if not CONFIG_PATH.exists():
    save_config(config)

UDP_IP = config["udp_ip"]
UDP_PORT = int(config["udp_port"])
TCP_IP = config.get("tcp_ip", UDP_IP)
TCP_PORT = int(config.get("tcp_port", UDP_PORT))
SENSITIVITY = float(config["sensitivity"])
SCREEN_WIDTH = int(config["screen_width"])
ZOOM_LEVEL = int(config["zoom_level"])
ANGLE_DEAD_ZONE = float(config["angle_dead_zone"])
INTERPOLATION_SLEEP = float(config["interpolation_sleep"])
ACCEL_INTERPOLATION_STEPS = max(1, int(config.get("accel_interpolation_steps", 3)))
ACCEL_FILTER_ALPHA = float(config.get("accel_filter_alpha", 0.35))
ACCEL_TARGET_HYSTERESIS_PX = max(
    ACCEL_INTERPOLATION_STEPS,
    int(config.get("accel_target_hysteresis_steps", 1)) * ACCEL_INTERPOLATION_STEPS
)

ACCEL_COEFFICIENT = SCREEN_WIDTH / 9.8
MIDPOINT = SCREEN_WIDTH // ZOOM_LEVEL // 2

is_controlling = True
accel_zero_g = float(config.get("accel_zero_g", 0.0))
accel_filtered_value = 0.0
gyro_remainder = 0.0

keys_table = [
    "shift", "a", "s", "d", "f", "space"
]
current_keys_state = [0] * len(keys_table)

pydirectinput.PAUSE = 0

move_target_x = MIDPOINT
last_queued_target_x = MIDPOINT
move_steps_remaining = 0
move_target_lock = threading.Lock()
move_target_event = threading.Event()
move_stop_event = threading.Event()
server_stop_event = threading.Event()

user32 = windll.user32

INPUT_MOUSE = 0
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_ABSOLUTE = 0x8000
MOUSEEVENTF_VIRTUALDESK = 0x4000
SM_XVIRTUALSCREEN = 76
SM_YVIRTUALSCREEN = 77
SM_CXVIRTUALSCREEN = 78
SM_CYVIRTUALSCREEN = 79


class POINT(Structure):
    _fields_ = [("x", c_long), ("y", c_long)]


class MOUSEINPUT(Structure):
    _fields_ = [
        ("dx", c_long),
        ("dy", c_long),
        ("mouseData", c_ulong),
        ("dwFlags", c_ulong),
        ("time", c_ulong),
        ("dwExtraInfo", c_void_p),
    ]


class INPUTUNION(Union):
    _fields_ = [("mi", MOUSEINPUT)]


class INPUT(Structure):
    _fields_ = [("type", c_ulong), ("union", INPUTUNION)]


def get_cursor_position():
    point = POINT()
    if not user32.GetCursorPos(byref(point)):
        raise OSError("GetCursorPos failed")
    return point.x, point.y


def normalize_absolute_coordinate(value, origin, span):
    if span <= 1:
        return 0
    clamped = max(origin, min(value, origin + span - 1))
    return round((clamped - origin) * 65535 / (span - 1))


def move_mouse_absolute(x, y):
    virtual_left = user32.GetSystemMetrics(SM_XVIRTUALSCREEN)
    virtual_top = user32.GetSystemMetrics(SM_YVIRTUALSCREEN)
    virtual_width = user32.GetSystemMetrics(SM_CXVIRTUALSCREEN)
    virtual_height = user32.GetSystemMetrics(SM_CYVIRTUALSCREEN)

    mouse_input = MOUSEINPUT(
        dx=normalize_absolute_coordinate(x, virtual_left, virtual_width),
        dy=normalize_absolute_coordinate(y, virtual_top, virtual_height),
        mouseData=0,
        dwFlags=MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK,
        time=0,
        dwExtraInfo=0,
    )
    input_record = INPUT(type=INPUT_MOUSE, union=INPUTUNION(mi=mouse_input))
    if user32.SendInput(1, byref(input_record), sizeof(INPUT)) != 1:
        raise OSError("SendInput failed")


def log(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}")


def set_accel_zero(new_zero):
    global accel_zero_g, accel_filtered_value, config
    accel_zero_g = float(new_zero)
    accel_filtered_value = 0.0
    config["accel_zero_g"] = accel_zero_g
    save_config(config)
    log(f"Saved accelerometer zero g = {accel_zero_g:.5f}")


def on_press(key):
    global is_controlling
    try:
        if key == keyboard.Key.backspace:
            is_controlling = not is_controlling
            log(f"Control {'enabled' if is_controlling else 'disabled'}")
    except AttributeError:
        pass


def quantize_accel_target(raw_target_x):
    delta_from_midpoint = raw_target_x - MIDPOINT
    quantized_delta = round(delta_from_midpoint / ACCEL_INTERPOLATION_STEPS) * ACCEL_INTERPOLATION_STEPS
    return MIDPOINT + quantized_delta


def queue_accel_target(raw_value):
    global accel_filtered_value, move_target_x, move_steps_remaining, last_queued_target_x
    adjusted_value = (raw_value*SENSITIVITY) - accel_zero_g
    accel_filtered_value += (adjusted_value - accel_filtered_value) * ACCEL_FILTER_ALPHA
    filtered_value = 0.0 if abs(accel_filtered_value) < ANGLE_DEAD_ZONE else accel_filtered_value
    raw_target_x = math.floor(filtered_value * ACCEL_COEFFICIENT + MIDPOINT)
    target_x = quantize_accel_target(raw_target_x)

    current_x, current_y = get_cursor_position()
    move_mouse_absolute(target_x, current_y)
    '''if abs(target_x - MIDPOINT) <= ACCEL_TARGET_HYSTERESIS_PX:
        target_x = MIDPOINT
    with move_target_lock:
        if target_x == last_queued_target_x and move_steps_remaining > 0:
            return
        if abs(target_x - move_target_x) < ACCEL_TARGET_HYSTERESIS_PX and target_x != MIDPOINT:
            return
        move_target_x = target_x
        last_queued_target_x = target_x
        move_steps_remaining = ACCEL_INTERPOLATION_STEPS
    move_target_event.set()'''


def move_to_midpoint():
    current_x, current_y = get_cursor_position()
    move_mouse_absolute(MIDPOINT, current_y)


def move_worker():
    global move_steps_remaining
    while not move_stop_event.is_set():
        move_target_event.wait(0.05)
        if move_stop_event.is_set():
            break
        if not move_target_event.is_set():
            continue

        with move_target_lock:
            target_x = move_target_x
            steps_remaining = move_steps_remaining

        current_x, current_y = get_cursor_position()
        delta_x = int(round(target_x - current_x))
        if delta_x == 0 or steps_remaining <= 0:
            with move_target_lock:
                if move_target_x == target_x:
                    move_steps_remaining = 0
                    move_target_event.clear()
            continue

        step_delta = int(round(delta_x / steps_remaining))
        if step_delta == 0:
            step_delta = 1 if delta_x > 0 else -1
        move_mouse_absolute(current_x + step_delta, current_y)

        with move_target_lock:
            if move_target_x == target_x:
                move_steps_remaining = max(0, move_steps_remaining - 1)
                if move_steps_remaining == 0:
                    move_target_event.clear()
                    print(f"Reached target x={target_x} with final delta={delta_x}")
        #time.sleep(INTERPOLATION_SLEEP)


def send_pause_key():
    pydirectinput.press("esc")
    log("Sent Esc pause toggle")


def apply_gyro(raw_value):
    global gyro_remainder
    adjusted_value = 0.0 if abs(raw_value) < ANGLE_DEAD_ZONE else raw_value
    total_delta = (-adjusted_value * SENSITIVITY / 2.0) + gyro_remainder
    hid_delta = math.trunc(total_delta)
    gyro_remainder = total_delta - hid_delta 
    if hid_delta != 0:
        pydirectinput.moveRel(xOffset=hid_delta, yOffset=0, relative=True)


def handle_message(message):
    raw_msg = message.decode().split("|", 1)
    if len(raw_msg) != 2:
        return

    key_type = raw_msg[0]
    key_para = raw_msg[1]

    if key_type == "AZ":
        set_accel_zero(float(key_para))
        return
    if key_type == "P":
        send_pause_key()
        return

    if not is_controlling:
        return

    if key_type == "RESET":
        move_to_midpoint()
    elif key_type == "A":
        queue_accel_target(float(key_para))
    elif key_type == "G":
        queue_accel_target(float(key_para))
    elif key_type == "M":
        apply_gyro(float(key_para))
    elif key_type == "K":
        for i, state in enumerate(str(key_para)):
            if i >= len(current_keys_state):
                break
            if state == "1" and current_keys_state[i] == 0:
                pydirectinput.keyDown(keys_table[i])
                current_keys_state[i] = 1
            elif state == "0" and current_keys_state[i] == 1:
                pydirectinput.keyUp(keys_table[i])
                current_keys_state[i] = 0


def tcp_client_worker(client_socket, client_addr):
    log(f"TCP client connected {client_addr[0]}:{client_addr[1]}")
    buffer = ""
    try:
        client_socket.settimeout(0.1)
        while not server_stop_event.is_set():
            try:
                chunk = client_socket.recv(1024)
                if not chunk:
                    break
                buffer += chunk.decode()
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    line = line.strip()
                    if line:
                        handle_message(line.encode())
            except socket.timeout:
                continue
    except OSError:
        pass
    finally:
        client_socket.close()
        log(f"TCP client disconnected {client_addr[0]}:{client_addr[1]}")


def tcp_server_worker(server_socket):
    log(f"TCP receiver listening on {TCP_IP}:{TCP_PORT}")
    server_socket.settimeout(0.1)
    while not server_stop_event.is_set():
        try:
            client_socket, client_addr = server_socket.accept()
            worker = threading.Thread(
                target=tcp_client_worker,
                args=(client_socket, client_addr),
                name=f"tcp-client-{client_addr[0]}:{client_addr[1]}",
                daemon=True,
            )
            worker.start()
        except socket.timeout:
            continue
        except OSError:
            break


listener = keyboard.Listener(on_press=on_press)
listener.start()

move_thread = threading.Thread(target=move_worker, name="move-worker", daemon=True)
move_thread.start()

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind((UDP_IP, UDP_PORT))
sock.settimeout(0.1)

tcp_server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
tcp_server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
tcp_server_socket.bind((TCP_IP, TCP_PORT))
tcp_server_socket.listen()

tcp_server_thread = threading.Thread(target=tcp_server_worker, args=(tcp_server_socket,), name="tcp-server", daemon=True)
tcp_server_thread.start()

log("Receiver started")
log(
    "udp=%s:%d tcp=%s:%d sensitivity=%.3f accel_steps=%d accel_zero=%.5f"
    % (UDP_IP, UDP_PORT, TCP_IP, TCP_PORT, SENSITIVITY, ACCEL_INTERPOLATION_STEPS, accel_zero_g)
)

try:
    while True:
        try:
            data, addr = sock.recvfrom(1024)
            handle_message(data)
        except socket.timeout:
            continue
except KeyboardInterrupt:
    log("Shutting down")
finally:
    server_stop_event.set()
    move_stop_event.set()
    move_target_event.set()
    move_thread.join(timeout=0.2)
    tcp_server_socket.close()
    tcp_server_thread.join(timeout=0.2)
    listener.stop()
    sock.close()
