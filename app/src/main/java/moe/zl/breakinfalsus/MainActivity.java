package moe.zl.breakinfalsus;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import android.Manifest;
import android.app.ActivityManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.Locale;

import moe.zl.breakinfalsus.input.SensorMouseController;
import moe.zl.breakinfalsus.input.SixKeyTouchLayout;
import moe.zl.breakinfalsus.output.BluetoothHidOutputTransport;
import moe.zl.breakinfalsus.output.OutputTransport;
import moe.zl.breakinfalsus.output.RootHidOutputTransport;
import moe.zl.breakinfalsus.output.TcpOutputTransport;
import moe.zl.breakinfalsus.output.UdpOutputTransport;

public class MainActivity extends AppCompatActivity implements SixKeyTouchLayout.OnKeyStateChangeListener {
    private static final String MODE_UDP = "UDP";
    private static final String MODE_TCP = "TCP";
    private static final String MODE_HID = "ROOT_HID";
    private static final String MODE_BT_HID = "BT_HID";
    private static final String SENSOR_GRAVITY = "GRAVITY";
    private static final String SENSOR_ACCEL = "ACCEL";
    private static final String SENSOR_GYRO = "GYRO";
    private static final String PREFS_NAME = "controller_prefs";
    private static final String PREF_HOST = "host";
    private static final String PREF_PORT = "port";
    private static final String PREF_BLUETOOTH_DEVICE = "bluetooth_device";
    private static final String PREF_KEYBOARD_HID = "keyboard_hid";
    private static final String PREF_MOUSE_HID = "mouse_hid";
    private static final String PREF_KEYBOARD_OUTPUT = "keyboard_output";
    private static final String PREF_MOUSE_OUTPUT = "mouse_output";
    private static final String PREF_SENSOR_MODE = "sensor_mode";
    private static final String PREF_SENSITIVITY = "sensitivity";
    private static final String PREF_DEADZONE = "deadzone";
    private static final String PREF_ACCEL_ZERO_G = "accel_zero_g";
    private static final String PREF_CHORD_BUFFER = "chord_buffer";
    private static final String PREF_KEY_WIDTH_RATIOS = "key_width_ratios";
    private static final String PREF_MOTION_LOG_ENABLED = "motion_log_enabled";
    private static final String PREF_PANEL_HIDDEN = "panel_hidden";
    private static final long PANEL_ANIMATION_DURATION_MS = 200L;
    private static final int ACCEL_MOVE_STEPS = 3;
    private static final int REQUEST_BT_PERMISSIONS = 1001;

    private SixKeyTouchLayout touchPad;
    private SensorMouseController sensorMouseController;
    private TextInputEditText hostInput;
    private TextInputEditText portInput;
    private TextInputEditText bluetoothDeviceInput;
    private TextInputEditText keyboardHidInput;
    private TextInputEditText mouseHidInput;
    private TextInputEditText keyWidthRatiosInput;
    private Spinner keyboardOutputSpinner;
    private Spinner mouseOutputSpinner;
    private Spinner sensorModeSpinner;
    private View rootContent;
    private View topBar;
    private View settingsPanel;
    private MaterialButton showSettingsButton;

    private MaterialButton resetButton;
    private MaterialButton pauseButton;
    private MaterialButton calibrateButton;
    private MaterialButton lockTaskButton;
    private Slider sensitivitySlider;
    private Slider deadzoneSlider;
    private Slider chordBufferSlider;
    private TextView statusText;
    private SwitchMaterial motionLogsSwitch;

    private OutputTransport keyboardTransport;
    private OutputTransport mouseTransport;
    private SharedPreferences preferences;
    private String[] outputModes;
    private String[] sensorModes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        setContentView(R.layout.activity_main);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        bindViews();
        applyWindowInsets();
        setupDropdowns();
        sensorMouseController = new SensorMouseController(
                this,
                new SensorMouseController.MouseSignalListener() {
                    @Override
                    public void onAccelerometerValue(float value, int hidDelta) {
                        if (mouseTransport == null) {
                            return;
                        }
                        mouseTransport.sendAccelerometer(value);
                        sendInterpolatedMouseMove(mouseTransport, hidDelta, ACCEL_MOVE_STEPS);
                        updateStatus(String.format(Locale.US, "Accel %.3f -> %d", value, hidDelta));
                    }

                    @Override
                    public void onGyroscopeValue(float value, int hidDelta) {
                        if (mouseTransport == null) {
                            return;
                        }
                        mouseTransport.sendGyroscope(value);
                        mouseTransport.sendMouseMove(hidDelta, 0);
                        updateStatus(String.format(Locale.US, "Gyro %.3f -> %d", value, hidDelta));
                    }

                    @Override
                    public void onGravityValue(float value, int hidDelta) {
                        if (mouseTransport == null) {
                            return;
                        }
                        mouseTransport.sendGravity(value);
                        sendInterpolatedMouseMove(mouseTransport, hidDelta, ACCEL_MOVE_STEPS);
                        updateStatus(String.format(Locale.US, "Gravity %.3f -> %d", value, hidDelta));
                    }
                }
        );
        setupActions();
        restorePreferences();
        applyConfiguration();
        updateLockTaskButtonLabel();
        touchPad.setOnKeyStateChangeListener(this);
    }

    private void bindViews() {
        touchPad = findViewById(R.id.touchPad);
        rootContent = findViewById(R.id.rootContent);
        topBar = findViewById(R.id.topBar);
        hostInput = findViewById(R.id.hostInput);
        portInput = findViewById(R.id.portInput);
        bluetoothDeviceInput = findViewById(R.id.bluetoothDeviceInput);
        keyboardHidInput = findViewById(R.id.keyboardHidInput);
        mouseHidInput = findViewById(R.id.mouseHidInput);
        keyWidthRatiosInput = findViewById(R.id.keyWidthRatiosInput);
        keyboardOutputSpinner = findViewById(R.id.keyboardOutputSpinner);
        mouseOutputSpinner = findViewById(R.id.mouseOutputSpinner);
        sensorModeSpinner = findViewById(R.id.sensorModeSpinner);
        settingsPanel = findViewById(R.id.settingsPanel);
        showSettingsButton = findViewById(R.id.showSettingsButton);
        resetButton = findViewById(R.id.resetButton);
        pauseButton = findViewById(R.id.pauseButton);
        calibrateButton = findViewById(R.id.calibrateButton);
        lockTaskButton = findViewById(R.id.lockTaskButton);
        sensitivitySlider = findViewById(R.id.sensitivitySlider);
        deadzoneSlider = findViewById(R.id.deadzoneSlider);
        chordBufferSlider = findViewById(R.id.chordBufferSlider);
        statusText = findViewById(R.id.statusText);
        motionLogsSwitch = findViewById(R.id.motion_logs);
    }

    private void setupDropdowns() {
        outputModes = new String[]{MODE_UDP, MODE_TCP, MODE_HID, MODE_BT_HID};
        sensorModes = new String[]{SENSOR_GRAVITY, SENSOR_GYRO, SENSOR_ACCEL};
        ArrayAdapter<String> outputAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, outputModes);
        outputAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        ArrayAdapter<String> sensorAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, sensorModes);
        sensorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        keyboardOutputSpinner.setAdapter(outputAdapter);
        mouseOutputSpinner.setAdapter(outputAdapter);
        sensorModeSpinner.setAdapter(sensorAdapter);
    }

    private void setupActions() {
        MaterialButton applyButton = findViewById(R.id.applyButton);
        applyButton.setOnClickListener(view -> {
            applyConfiguration();
            savePreferences();
            hideSettingsPanel();
        });
        showSettingsButton.setOnClickListener(view -> {
            showSettingsPanel();
        });
        pauseButton.setOnClickListener(view -> {
            if (keyboardTransport != null) {
                keyboardTransport.sendPauseToggle();
                updateStatus("Esc pause sent");
            }
        });
        calibrateButton.setOnClickListener(view -> calibrateAccelerometer());
        lockTaskButton.setOnClickListener(view -> toggleLockTaskMode());
        sensitivitySlider.addOnChangeListener((slider, value, fromUser) -> {
            if (sensorMouseController != null) {
                sensorMouseController.setSensitivity(value);
            }
        });
        deadzoneSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (sensorMouseController != null) {
                sensorMouseController.setDeadzone(value);
            }
        });
        chordBufferSlider.addOnChangeListener((slider, value, fromUser) -> {
            touchPad.setChordBufferDp(value);
            if (fromUser) {
                updateStatus(String.format(Locale.US, "Chord buffer %.0fdp", value));
            }
        });
        motionLogsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            touchPad.setMotionLogEnabled(isChecked);
        });
    }

    private void applyConfiguration() {
        if (needsBluetoothPermissions() && !ensureBluetoothPermissions()) {
            return;
        }
        String ratioInput = getKeyWidthRatioInput();
        if (!touchPad.setKeyWidthRatios(ratioInput)) {
            updateStatus(getString(R.string.invalid_key_width_ratios));
            return;
        }
        closeTransport(keyboardTransport);
        closeTransport(mouseTransport);
        keyboardTransport = buildTransport(getSelectedOutputMode(keyboardOutputSpinner));
        mouseTransport = buildTransport(getSelectedOutputMode(mouseOutputSpinner));
        sensorMouseController.setMode(getSelectedSensorControllerMode());
        sensorMouseController.setSensitivity(sensitivitySlider.getValue());
        sensorMouseController.setDeadzone(deadzoneSlider.getValue());
        sensorMouseController.setAccelerometerZero(preferences.getFloat(PREF_ACCEL_ZERO_G, 0f));
        touchPad.setChordBufferDp(chordBufferSlider.getValue());
        touchPad.setMotionLogEnabled(motionLogsSwitch.isChecked());
        if (mouseTransport != null) {
            mouseTransport.sendAccelerometerCalibration(sensorMouseController.getAccelerometerZero());
        }
        updateStatus(getString(R.string.configuration_applied));
        if (mouseTransport != null && mouseTransport.supportsReset()) {
            resetButton.setVisibility(VISIBLE);
            resetButton.setOnClickListener((View v) -> mouseTransport.sendReset());
        } else {
            resetButton.setVisibility(INVISIBLE);
        }
    }

    private void hideSettingsPanel() {
        if (settingsPanel.getVisibility() == VISIBLE) {
            settingsPanel.animate().cancel();
            showSettingsButton.animate().cancel();
            settingsPanel.animate()
                    .alpha(0f)
                    .translationY(-settingsPanel.getHeight() * 0.2f)
                    .setDuration(PANEL_ANIMATION_DURATION_MS)
                    .withEndAction(() -> {
                        settingsPanel.setVisibility(View.GONE);
                        settingsPanel.setAlpha(1f);
                        settingsPanel.setTranslationY(0f);
                        revealSettingsButton();
                    })
                    .start();
        }
    }

    private void showSettingsPanel() {
        if (settingsPanel.getVisibility() != VISIBLE) {
            showSettingsButton.animate().cancel();
            showSettingsButton.setVisibility(View.GONE);
            settingsPanel.animate().cancel();
            settingsPanel.setVisibility(VISIBLE);
            settingsPanel.setAlpha(0f);
            settingsPanel.setTranslationY(-settingsPanel.getHeight() * 0.2f - dp(8));
            settingsPanel.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(PANEL_ANIMATION_DURATION_MS)
                    .start();
        }
    }

    private void revealSettingsButton() {
        showSettingsButton.setAlpha(0f);
        showSettingsButton.setScaleX(0.92f);
        showSettingsButton.setScaleY(0.92f);
        showSettingsButton.setVisibility(VISIBLE);
        showSettingsButton.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(PANEL_ANIMATION_DURATION_MS)
                .start();
    }

    private void setPanelVisibilityImmediately(boolean hidden) {
        settingsPanel.animate().cancel();
        showSettingsButton.animate().cancel();
        if (hidden) {
            settingsPanel.setVisibility(View.GONE);
            settingsPanel.setAlpha(1f);
            settingsPanel.setTranslationY(0f);
            showSettingsButton.setVisibility(VISIBLE);
            showSettingsButton.setAlpha(1f);
            showSettingsButton.setScaleX(1f);
            showSettingsButton.setScaleY(1f);
        } else {
            settingsPanel.setVisibility(VISIBLE);
            settingsPanel.setAlpha(1f);
            settingsPanel.setTranslationY(0f);
            showSettingsButton.setVisibility(View.GONE);
        }
    }

    private void restorePreferences() {
        hostInput.setText(preferences.getString(PREF_HOST, "192.168.0.2"));
        portInput.setText(preferences.getString(PREF_PORT, "9527"));
        bluetoothDeviceInput.setText(preferences.getString(PREF_BLUETOOTH_DEVICE, ""));
        keyboardHidInput.setText(preferences.getString(PREF_KEYBOARD_HID, "/dev/hidg0"));
        mouseHidInput.setText(preferences.getString(PREF_MOUSE_HID, "/dev/hidg1"));
        keyWidthRatiosInput.setText(preferences.getString(PREF_KEY_WIDTH_RATIOS, "1.2:1:1:1:1:1.2"));
        sensitivitySlider.setValue(preferences.getFloat(PREF_SENSITIVITY, 20f));
        deadzoneSlider.setValue(preferences.getFloat(PREF_DEADZONE, 0.05f));
        sensorMouseController.setAccelerometerZero(preferences.getFloat(PREF_ACCEL_ZERO_G, 0f));
        chordBufferSlider.setValue(preferences.getFloat(PREF_CHORD_BUFFER, 4f));
        motionLogsSwitch.setChecked(preferences.getBoolean(PREF_MOTION_LOG_ENABLED, false));
        setSpinnerSelection(keyboardOutputSpinner, outputModes, preferences.getString(PREF_KEYBOARD_OUTPUT, MODE_UDP));
        setSpinnerSelection(mouseOutputSpinner, outputModes, preferences.getString(PREF_MOUSE_OUTPUT, MODE_UDP));
        setSpinnerSelection(sensorModeSpinner, sensorModes, preferences.getString(PREF_SENSOR_MODE, SENSOR_GRAVITY));
        setPanelVisibilityImmediately(preferences.getBoolean(PREF_PANEL_HIDDEN, false));
    }

    private void savePreferences() {
        preferences.edit()
                .putString(PREF_HOST, getText(hostInput))
                .putString(PREF_PORT, getText(portInput))
                .putString(PREF_BLUETOOTH_DEVICE, getText(bluetoothDeviceInput))
                .putString(PREF_KEYBOARD_HID, getText(keyboardHidInput))
                .putString(PREF_MOUSE_HID, getText(mouseHidInput))
                .putString(PREF_KEY_WIDTH_RATIOS, getKeyWidthRatioInput())
                .putString(PREF_KEYBOARD_OUTPUT, getSelectedOutputMode(keyboardOutputSpinner))
                .putString(PREF_MOUSE_OUTPUT, getSelectedOutputMode(mouseOutputSpinner))
                .putString(PREF_SENSOR_MODE, getSelectedSensorMode())
                .putFloat(PREF_SENSITIVITY, sensitivitySlider.getValue())
                .putFloat(PREF_DEADZONE, deadzoneSlider.getValue())
                .putFloat(PREF_ACCEL_ZERO_G, sensorMouseController.getAccelerometerZero())
                .putFloat(PREF_CHORD_BUFFER, chordBufferSlider.getValue())
                .putBoolean(PREF_MOTION_LOG_ENABLED, motionLogsSwitch.isChecked())
                .putBoolean(PREF_PANEL_HIDDEN, true)
                .apply();
    }

    private String getSelectedOutputMode(@NonNull Spinner spinner) {
        Object value = spinner.getSelectedItem();
        return value == null ? MODE_UDP : value.toString();
    }

    private String getSelectedSensorMode() {
        Object value = sensorModeSpinner.getSelectedItem();
        return value == null ? SENSOR_GRAVITY : value.toString();
    }

    private SensorMouseController.Mode getSelectedSensorControllerMode() {
        String selectedMode = getSelectedSensorMode();
        if (SENSOR_ACCEL.equalsIgnoreCase(selectedMode)) {
            return SensorMouseController.Mode.ACCELEROMETER;
        }
        if (SENSOR_GYRO.equalsIgnoreCase(selectedMode)) {
            return SensorMouseController.Mode.GYROSCOPE;
        }
        return SensorMouseController.Mode.GRAVITY;
    }

    private void setSpinnerSelection(@NonNull Spinner spinner, @NonNull String[] values, @NonNull String wanted) {
        for (int i = 0; i < values.length; i++) {
            if (wanted.equalsIgnoreCase(values[i])) {
                spinner.setSelection(i);
                return;
            }
        }
        spinner.setSelection(0);
    }

    private String getText(@NonNull TextInputEditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    @NonNull
    private String getKeyWidthRatioInput() {
        String value = getText(keyWidthRatiosInput);
        return value.isEmpty() ? "1.2:1:1:1:1:1.2" : value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private OutputTransport buildTransport(@NonNull String mode) {
        if (MODE_BT_HID.equalsIgnoreCase(mode)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                Toast.makeText(this, R.string.bluetooth_hid_requires_android_p, Toast.LENGTH_SHORT).show();
                return null;
            }
            return new BluetoothHidOutputTransport(this, getText(bluetoothDeviceInput));
        }
        if (MODE_HID.equalsIgnoreCase(mode)) {
            String keyboardPath = getText(keyboardHidInput).isEmpty() ? "/dev/hidg0" : getText(keyboardHidInput);
            String mousePath = getText(mouseHidInput).isEmpty() ? "/dev/hidg1" : getText(mouseHidInput);
            return new RootHidOutputTransport(keyboardPath, mousePath);
        }
        String host = getText(hostInput);
        String portString = getText(portInput);
        int port = 9527;
        if (!TextUtils.isEmpty(portString)) {
            try {
                port = Integer.parseInt(portString);
            } catch (NumberFormatException ignored) {
                Toast.makeText(this, R.string.invalid_port_fallback, Toast.LENGTH_SHORT).show();
            }
        }
        if (MODE_TCP.equalsIgnoreCase(mode)) {
            return new TcpOutputTransport(host, port);
        }
        return new UdpOutputTransport(host, port);
    }

    private boolean needsBluetoothPermissions() {
        return MODE_BT_HID.equalsIgnoreCase(getSelectedOutputMode(keyboardOutputSpinner))
                || MODE_BT_HID.equalsIgnoreCase(getSelectedOutputMode(mouseOutputSpinner));
    }

    private boolean ensureBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        boolean hasConnect = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasAdvertise = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED;
        if (hasConnect && hasAdvertise) {
            return true;
        }
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE},
                REQUEST_BT_PERMISSIONS
        );
        Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_SHORT).show();
        return false;
    }

    private void updateStatus(@NonNull String message) {
        statusText.setText(message);
    }

    private void calibrateAccelerometer() {
        float zeroG = sensorMouseController.calibrateAccelerometerZero();
        preferences.edit().putFloat(PREF_ACCEL_ZERO_G, zeroG).apply();
        if (mouseTransport != null) {
            mouseTransport.sendAccelerometerCalibration(zeroG);
        }
        updateStatus(String.format(Locale.US, "Accel zero saved %.3fg", zeroG));
    }

    private void sendInterpolatedMouseMove(@NonNull OutputTransport transport, int totalDeltaX, int steps) {
        if (totalDeltaX == 0 || steps <= 1) {
            transport.sendMouseMove(totalDeltaX, 0);
            return;
        }
        int movedX = 0;
        for (int step = 1; step <= steps; step++) {
            int interpolatedX = Math.round(totalDeltaX * step / (float) steps);
            int stepDeltaX = interpolatedX - movedX;
            if (stepDeltaX != 0) {
                transport.sendMouseMove(stepDeltaX, 0);
                movedX += stepDeltaX;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sensorMouseController.start();
        updateLockTaskButtonLabel();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorMouseController.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeTransport(keyboardTransport);
        closeTransport(mouseTransport);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BT_PERMISSIONS) {
            return;
        }
        boolean granted = true;
        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }
        if (granted) {
            applyConfiguration();
            savePreferences();
        } else {
            updateStatus(getString(R.string.bluetooth_permission_required));
        }
    }

    private void toggleLockTaskMode() {
        try {
            if (isLockTaskActive()) {
                stopLockTask();
                updateStatus("Lock task stopped");
            } else {
                startLockTask();
                updateStatus("Lock task requested");
            }
            updateLockTaskButtonLabel();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            updateStatus("Lock task unavailable");
        }
    }

    @Override
    public void onKeyStateChanged(boolean[] keyStates) {
        if (keyboardTransport != null) {
            keyboardTransport.sendKeyboardState(keyStates);
            updateStatus("Keys " + encodeKeys(keyStates));
        }
    }

    private String encodeKeys(boolean[] keyStates) {
        StringBuilder builder = new StringBuilder(keyStates.length);
        for (boolean keyState : keyStates) {
            builder.append(keyState ? '1' : '0');
        }
        return builder.toString();
    }

    private void closeTransport(OutputTransport transport) {
        if (transport != null) {
            transport.close();
        }
    }

    private void applyWindowInsets() {
        if (rootContent == null) {
            return;
        }
        final int rootPaddingLeft = rootContent.getPaddingLeft();
        final int rootPaddingTop = rootContent.getPaddingTop();
        final int rootPaddingRight = rootContent.getPaddingRight();
        final int rootPaddingBottom = rootContent.getPaddingBottom();
        final int topBarPaddingLeft = topBar == null ? 0 : topBar.getPaddingLeft();
        final int topBarPaddingTop = topBar == null ? 0 : topBar.getPaddingTop();
        final int topBarPaddingRight = topBar == null ? 0 : topBar.getPaddingRight();
        final int topBarPaddingBottom = topBar == null ? 0 : topBar.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(rootContent, (view, insets) -> {
            Insets statusInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            Insets cutoutInsets = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            Insets navigationInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            int topInset = Math.max(statusInsets.top, cutoutInsets.top);
            int leftInset = Math.max(statusInsets.left, cutoutInsets.left);
            int rightInset = Math.max(statusInsets.right, cutoutInsets.right);
            int bottomInset = Math.max(cutoutInsets.bottom, navigationInsets.bottom);

            view.setPadding(
                    rootPaddingLeft,
                    rootPaddingTop,
                    rootPaddingRight,
                    rootPaddingBottom
            );
            if (topBar != null) {
                topBar.setPadding(
                        topBarPaddingLeft,
                        topBarPaddingTop + topInset,
                        topBarPaddingRight,
                        topBarPaddingBottom
                );
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(rootContent);
    }

    private boolean isLockTaskActive() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        ActivityManager activityManager = getSystemService(ActivityManager.class);
        return activityManager != null
                && activityManager.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    private void updateLockTaskButtonLabel() {
        if (lockTaskButton == null) {
            return;
        }
        lockTaskButton.setText(isLockTaskActive() ? R.string.lock_task_btn : R.string.unlock_task_btn);
    }
}
