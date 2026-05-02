#[cfg(target_os = "windows")]
use enigo::{Enigo, Keyboard, Mouse, Key, Direction, Coordinate, Settings};

#[cfg(target_os = "linux")]
use evdev::{
    uinput::VirtualDevice, AbsInfo, AbsoluteAxisCode, AttributeSet, EventType, InputEvent,
    KeyCode as EvKey, RelativeAxisCode, UinputAbsSetup,
};

use std::sync::Mutex;
use lazy_static::lazy_static;

pub struct InputBackend {
    #[cfg(target_os = "windows")]
    enigo: Mutex<Enigo>,
    
    #[cfg(target_os = "linux")]
    device: Mutex<Option<VirtualDevice>>,
}

lazy_static! {
    pub static ref INPUT_BACKEND: InputBackend = InputBackend::new();
}

impl InputBackend {
    #[cfg(target_os = "windows")]
    fn new() -> Self {
        Self {
            enigo: Mutex::new(Enigo::new(&Settings::default()).unwrap()),
        }
    }

    #[cfg(target_os = "linux")]
    fn new() -> Self {
        Self {
            device: Mutex::new(None),
        }
    }

    #[cfg(target_os = "windows")]
    pub fn init(&self, _use_absolute: bool, _screen_width: u32) {
    }

    #[cfg(target_os = "linux")]
    pub fn init(&self, use_absolute: bool, screen_width: u32) {
        let mut keys = AttributeSet::<EvKey>::new();
        keys.insert(EvKey::BTN_LEFT);
        keys.insert(EvKey::BTN_RIGHT);
        keys.insert(EvKey::BTN_MIDDLE);
        keys.insert(EvKey::KEY_Q);
        keys.insert(EvKey::KEY_A);
        keys.insert(EvKey::KEY_S);
        keys.insert(EvKey::KEY_D);
        keys.insert(EvKey::KEY_F);
        keys.insert(EvKey::KEY_SPACE);
        keys.insert(EvKey::KEY_ESC);
        
        let mut builder = VirtualDevice::builder().unwrap().name("phone-linux-uinput-controller");
        builder = builder.with_keys(&keys).unwrap();

        if use_absolute {
            keys.insert(EvKey::BTN_TOUCH);
            builder = builder.with_keys(&keys).unwrap();

            let max = screen_width as i32;
            let abs_x = UinputAbsSetup::new(
                AbsoluteAxisCode::ABS_X,
                AbsInfo::new(0, 0, max, 0, 0, 0),
            );
            let abs_y = UinputAbsSetup::new(
                AbsoluteAxisCode::ABS_Y,
                AbsInfo::new(0, 0, max, 0, 0, 0),
            );
            builder = builder
                .with_absolute_axis(&abs_x)
                .unwrap()
                .with_absolute_axis(&abs_y)
                .unwrap();
        } else {
            let mut rels = AttributeSet::<RelativeAxisCode>::new();
            rels.insert(RelativeAxisCode::REL_X);
            rels.insert(RelativeAxisCode::REL_Y);
            rels.insert(RelativeAxisCode::REL_WHEEL);
            builder = builder.with_relative_axes(&rels).unwrap();
        }

        match builder.build() {
            Ok(dev) => {
                println!(
                    "Created Linux uinput device in {} mode",
                    if use_absolute { "absolute" } else { "relative" }
                );
                let mut device_guard = self.device.lock().unwrap();
                *device_guard = Some(dev);
            }
            Err(err) => {
                eprintln!(
                    "Failed to create uinput device: {}. Check /dev/uinput permissions or run with proper privileges.",
                    err
                );
            }
        }
    }

    #[cfg(target_os = "windows")]
    pub fn move_rel(&self, dx: i32, dy: i32, _use_absolute: bool, _abs_x: i32, _abs_y: i32) {
        if dx != 0 || dy != 0 {
            let mut enigo = self.enigo.lock().unwrap();
            let _ = enigo.move_mouse(dx, dy, Coordinate::Rel);
        }
    }

    #[cfg(target_os = "windows")]
    pub fn mouse_location(&self) -> Option<(i32, i32)> {
        let enigo = self.enigo.lock().unwrap();
        enigo.location().ok()
    }

    #[cfg(target_os = "linux")]
    pub fn mouse_location(&self) -> Option<(i32, i32)> {
        None
    }

    #[cfg(target_os = "linux")]
    pub fn move_rel(&self, dx: i32, dy: i32, use_absolute: bool, abs_x: i32, abs_y: i32) {
        if dx == 0 && dy == 0 {
            return;
        }
        let mut device_guard = self.device.lock().unwrap();
            if let Some(dev) = device_guard.as_mut() {
                let events = if use_absolute {
                vec![
                    InputEvent::new_now(EventType::ABSOLUTE.0, AbsoluteAxisCode::ABS_X.0, abs_x as i32),
                    InputEvent::new_now(EventType::ABSOLUTE.0, AbsoluteAxisCode::ABS_Y.0, abs_y as i32),
                ]
            } else {
                let mut v = vec![];
                if dx != 0 { v.push(InputEvent::new_now(EventType::RELATIVE.0, RelativeAxisCode::REL_X.0, dx as i32)); }
                if dy != 0 { v.push(InputEvent::new_now(EventType::RELATIVE.0, RelativeAxisCode::REL_Y.0, dy as i32)); }
                v
            };
            if let Err(err) = dev.emit(&events) {
                eprintln!("Failed to emit mouse event: {}", err);
            }
        } else {
            eprintln!("Input device is not initialized; mouse event ignored");
        }
    }

    #[cfg(target_os = "windows")]
    pub fn key_down(&self, key: &str) {
        if let Some(k) = Self::map_key(key) {
            let mut enigo = self.enigo.lock().unwrap();
            let _ = enigo.key(k, Direction::Press);
        }
    }

    #[cfg(target_os = "windows")]
    pub fn key_up(&self, key: &str) {
        if let Some(k) = Self::map_key(key) {
            let mut enigo = self.enigo.lock().unwrap();
            let _ = enigo.key(k, Direction::Release);
        }
    }

    #[cfg(target_os = "linux")]
    pub fn key_down(&self, key: &str) {
        if let Some(code) = Self::map_key(key) {
            let mut device_guard = self.device.lock().unwrap();
            if let Some(dev) = device_guard.as_mut() {
                if let Err(err) = dev.emit(&[InputEvent::new_now(EventType::KEY.0, code.0, 1)]) {
                    eprintln!("Failed to emit key down event for {}: {}", key, err);
                }
            } else {
                eprintln!("Input device is not initialized; key down ignored: {}", key);
            }
        }
    }

    #[cfg(target_os = "linux")]
    pub fn key_up(&self, key: &str) {
        if let Some(code) = Self::map_key(key) {
            let mut device_guard = self.device.lock().unwrap();
            if let Some(dev) = device_guard.as_mut() {
                if let Err(err) = dev.emit(&[InputEvent::new_now(EventType::KEY.0, code.0, 0)]) {
                    eprintln!("Failed to emit key up event for {}: {}", key, err);
                }
            } else {
                eprintln!("Input device is not initialized; key up ignored: {}", key);
            }
        }
    }

    pub fn key_press(&self, key: &str) {
        self.key_down(key);
        std::thread::sleep(std::time::Duration::from_millis(10));
        self.key_up(key);
    }

    #[cfg(target_os = "windows")]
    fn map_key(key: &str) -> Option<Key> {
        match key {
            "shift" => Some(Key::LShift),
            "q" => Some(Key::Q),
            "a" => Some(Key::A),
            "s" => Some(Key::S),
            "d" => Some(Key::D),
            "f" => Some(Key::F),
            "space" => Some(Key::Space),
            "esc" => Some(Key::Escape),
            _ => None
        }
    }

    #[cfg(target_os = "linux")]
    fn map_key(key: &str) -> Option<EvKey> {
        match key {
            "q" => Some(EvKey::KEY_Q),
            "a" => Some(EvKey::KEY_A),
            "s" => Some(EvKey::KEY_S),
            "d" => Some(EvKey::KEY_D),
            "f" => Some(EvKey::KEY_F),
            "space" => Some(EvKey::KEY_SPACE),
            "esc" => Some(EvKey::KEY_ESC),
            _ => None
        }
    }
}
