package moe.zl.breakinfalsus;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.topjohnwu.superuser.Shell;

import java.util.Locale;
import java.util.List;

import moe.zl.breakinfalsus.output.HidMouseMode;

public class UsbGadgetConfigActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "controller_prefs";
    private static final String PREF_GADGET_NAME = "hid_gadget_name";
    private static final String PREF_GADGET_VENDOR_ID = "hid_gadget_vendor_id";
    private static final String PREF_GADGET_PRODUCT_ID = "hid_gadget_product_id";
    private static final String PREF_GADGET_MANUFACTURER = "hid_gadget_manufacturer";
    private static final String PREF_GADGET_PRODUCT_NAME = "hid_gadget_product_name";
    private static final String PREF_GADGET_SERIAL = "hid_gadget_serial";
    private static final String PREF_HID_MOUSE_MODE = "hid_mouse_mode";
    private static final String PREF_GADGET_BIND_UDC = "hid_gadget_bind_udc";

    private static final String KEYBOARD_DESCRIPTOR =
            "\\x05\\x01\\x09\\x06\\xa1\\x01\\x05\\x07\\x19\\xe0\\x29\\xe7\\x15\\x00\\x25\\x01" +
            "\\x75\\x01\\x95\\x08\\x81\\x02\\x95\\x01\\x75\\x08\\x81\\x03\\x95\\x05\\x75\\x01" +
            "\\x05\\x08\\x19\\x01\\x29\\x05\\x91\\x02\\x95\\x01\\x75\\x03\\x91\\x03\\x95\\x06" +
            "\\x75\\x08\\x15\\x00\\x25\\x65\\x05\\x07\\x19\\x00\\x29\\x65\\x81\\x00\\xc0";
    private static final String RELATIVE_MOUSE_DESCRIPTOR =
            "\\x05\\x01\\x09\\x02\\xa1\\x01\\x09\\x01\\xa1\\x00\\x05\\x09\\x19\\x01\\x29\\x05" +
            "\\x15\\x00\\x25\\x01\\x95\\x05\\x75\\x01\\x81\\x02\\x95\\x01\\x75\\x03\\x81\\x01" +
            "\\x05\\x01\\x09\\x30\\x09\\x31\\x09\\x38\\x15\\x81\\x25\\x7f\\x75\\x08\\x95\\x03" +
            "\\x81\\x06\\xc0\\xc0";
    private static final String ABSOLUTE_MOUSE_DESCRIPTOR =
            "\\x05\\x01\\x09\\x02\\xa1\\x01\\x09\\x01\\xa1\\x00\\x05\\x09\\x19\\x01\\x29\\x03" +
            "\\x15\\x00\\x25\\x01\\x95\\x03\\x75\\x01\\x81\\x02\\x95\\x01\\x75\\x05\\x81\\x03" +
            "\\x05\\x01\\x16\\x00\\x00\\x26\\xff\\x7f\\x75\\x10\\x95\\x02\\x09\\x30\\x09\\x31" +
            "\\x81\\x02\\xc0\\xc0";

    private TextInputEditText gadgetNameInput;
    private TextInputEditText vendorIdInput;
    private TextInputEditText productIdInput;
    private TextInputEditText manufacturerInput;
    private TextInputEditText productNameInput;
    private TextInputEditText serialInput;
    private Spinner mouseModeSpinner;
    private SwitchMaterial bindUdcSwitch;
    private TextView statusText;
    private SharedPreferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_gadget_config);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        bindViews();
        setupMouseModeSpinner();
        restorePreferences();
        setupActions();
    }

    private void bindViews() {
        gadgetNameInput = findViewById(R.id.gadgetNameInput);
        vendorIdInput = findViewById(R.id.vendorIdInput);
        productIdInput = findViewById(R.id.productIdInput);
        manufacturerInput = findViewById(R.id.manufacturerInput);
        productNameInput = findViewById(R.id.productNameInput);
        serialInput = findViewById(R.id.serialInput);
        mouseModeSpinner = findViewById(R.id.mouseModeSpinner);
        bindUdcSwitch = findViewById(R.id.bindUdcSwitch);
        statusText = findViewById(R.id.gadgetStatusText);
    }

    private void setupMouseModeSpinner() {
        String[] values = new String[]{HidMouseMode.RELATIVE.name(), HidMouseMode.ABSOLUTE.name()};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mouseModeSpinner.setAdapter(adapter);
    }

    private void restorePreferences() {
        gadgetNameInput.setText(preferences.getString(PREF_GADGET_NAME, "breakinfalsus"));
        vendorIdInput.setText(preferences.getString(PREF_GADGET_VENDOR_ID, "0x046a"));
        productIdInput.setText(preferences.getString(PREF_GADGET_PRODUCT_ID, "0x002a"));
        manufacturerInput.setText(preferences.getString(PREF_GADGET_MANUFACTURER, "Breakin_Falsus"));
        productNameInput.setText(preferences.getString(PREF_GADGET_PRODUCT_NAME, "Breakin_Falsus_HID"));
        serialInput.setText(preferences.getString(PREF_GADGET_SERIAL, "39"));
        setMouseModeSelection(preferences.getString(PREF_HID_MOUSE_MODE, HidMouseMode.RELATIVE.name()));
        bindUdcSwitch.setChecked(preferences.getBoolean(PREF_GADGET_BIND_UDC, true));
    }

    private void setupActions() {
        MaterialButton applyButton = findViewById(R.id.createGadgetButton);
        MaterialButton restoreButton = findViewById(R.id.restoreSystemGadgetButton);
        MaterialButton closeButton = findViewById(R.id.closeGadgetButton);
        applyButton.setOnClickListener(view -> createOrUpdateGadget());
        restoreButton.setOnClickListener(view -> restoreSystemGadget());
        closeButton.setOnClickListener(view -> finish());
    }

    private void createOrUpdateGadget() {
        final String gadgetName = sanitizeGadgetName(getText(gadgetNameInput));
        if (TextUtils.isEmpty(gadgetName)) {
            updateStatus(getString(R.string.gadget_invalid_name));
            return;
        }
        final String vendorId = normalizeHexId(getText(vendorIdInput), "0x046a");
        final String productId = normalizeHexId(getText(productIdInput), "0x002a");
        final String manufacturer = fallback(getText(manufacturerInput), "Breakin_Falsus");
        final String productName = fallback(getText(productNameInput), "Breakin_Falsus_HID");
        final String serial = fallback(getText(serialInput), "39");
        final HidMouseMode mouseMode = HidMouseMode.fromPreference(String.valueOf(mouseModeSpinner.getSelectedItem()));
        final boolean bindUdc = bindUdcSwitch.isChecked();

        savePreferences(gadgetName, vendorId, productId, manufacturer, productName, serial, mouseMode, bindUdc);
        updateStatus(getString(R.string.gadget_status_working));

        new Thread(() -> {
            Shell.Result result = Shell.cmd(buildShellScript(
                    gadgetName,
                    vendorId,
                    productId,
                    manufacturer,
                    productName,
                    serial,
                    mouseMode,
                    bindUdc
            )).exec();
            runOnUiThread(() -> {
                String logOutput = buildResultLog(result);
                if (result.isSuccess()) {
                    String mouseNode = mouseMode == HidMouseMode.ABSOLUTE ? "/dev/hidg1 (absolute)" : "/dev/hidg1 (relative)";
                    updateStatus(getString(R.string.gadget_status_success, gadgetName, "/dev/hidg0", mouseNode) + "\n" + logOutput);
                } else {
                    String error = summarizeFailure(result, logOutput);
                    updateStatus(getString(R.string.gadget_status_failed, error));
                }
            });
        }).start();
    }

    private void restoreSystemGadget() {
        final String gadgetName = sanitizeGadgetName(getText(gadgetNameInput));
        if (TextUtils.isEmpty(gadgetName)) {
            updateStatus(getString(R.string.gadget_invalid_name));
            return;
        }
        updateStatus(getString(R.string.gadget_restore_working));
        new Thread(() -> {
            Shell.Result result = Shell.cmd(buildRestoreSystemShellScript(gadgetName)).exec();
            runOnUiThread(() -> {
                String logOutput = buildResultLog(result);
                if (result.isSuccess()) {
                    updateStatus(getString(R.string.gadget_restore_success) + "\n" + logOutput);
                } else {
                    String error = summarizeFailure(result, logOutput);
                    updateStatus(getString(R.string.gadget_restore_failed, error));
                }
            });
        }).start();
    }

    @NonNull
    private String buildShellScript(
            @NonNull String gadgetName,
            @NonNull String vendorId,
            @NonNull String productId,
            @NonNull String manufacturer,
            @NonNull String productName,
            @NonNull String serial,
            @NonNull HidMouseMode mouseMode,
            boolean bindUdc
    ) {
        String mouseDescriptor = mouseMode == HidMouseMode.ABSOLUTE
                ? ABSOLUTE_MOUSE_DESCRIPTOR
                : RELATIVE_MOUSE_DESCRIPTOR;
        int mouseReportLength = mouseMode == HidMouseMode.ABSOLUTE ? 5 : 4;
        StringBuilder builder = new StringBuilder();
        builder.append("set -e\n");
        builder.append("PS4='[sh] '\n");
        builder.append("set -x\n");
        builder.append("log(){ echo \"[usb-gadget] $*\"; }\n");
        builder.append("dump_file(){ if [ -e \"$1\" ]; then echo \"[dump] $1=$(cat \"$1\" 2>/dev/null)\"; else echo \"[dump] missing:$1\"; fi; }\n");
        builder.append("log \"start gadget=").append(escapeShell(gadgetName))
                .append(" mode=").append(mouseMode.name())
                .append(" bind_udc=").append(bindUdc ? "1" : "0").append("\"\n");
        builder.append("CONFIGFS_DIR=\"/config\"\n");
        builder.append("GADGETS_PATH=\"${CONFIGFS_DIR}/usb_gadget\"\n");
        builder.append("if [ ! -d \"$GADGETS_PATH\" ]; then\n");
        builder.append("  log \"/config/usb_gadget missing, try configfs mount fallback\"\n");
        builder.append("  mkdir -p /sys/kernel/config\n");
        builder.append("  mountpoint -q /sys/kernel/config || mount -t configfs none /sys/kernel/config || true\n");
        builder.append("  if [ -d /sys/kernel/config/usb_gadget ]; then\n");
        builder.append("    mkdir -p /config\n");
        builder.append("    mountpoint -q /config || mount --bind /sys/kernel/config /config || true\n");
        builder.append("  fi\n");
        builder.append("fi\n");
        builder.append("[ -d \"$GADGETS_PATH\" ] || { echo \"configfs usb_gadget not found at /config/usb_gadget\"; exit 1; }\n");
        builder.append("log \"use GADGETS_PATH=$GADGETS_PATH\"\n");
        builder.append("GADGET_PATH=\"$GADGETS_PATH/").append(escapeShell(gadgetName)).append("\"\n");
        builder.append("CONFIG_PATH=\"$GADGET_PATH/configs/c.1\"\n");
        builder.append("STRINGS_PATH=\"$GADGET_PATH/strings/0x409\"\n");
        builder.append("if [ -f \"$GADGET_PATH/UDC\" ]; then\n");
        builder.append("  log \"detach existing gadget UDC\"\n");
        builder.append("  echo \"\" > \"$GADGET_PATH/UDC\" || true\n");
        builder.append("fi\n");
        builder.append("log \"prepare gadget directories\"\n");
        builder.append("mkdir -p \"$CONFIG_PATH\"\n");
        builder.append("mkdir -p \"$STRINGS_PATH\"\n");
        builder.append("log \"configure gadget-level usb attributes\"\n");
        builder.append("cd \"$GADGET_PATH\"\n");
        builder.append("echo high-speed > max_speed\n");
        builder.append("echo 0x0200 > bcdUSB\n");
        builder.append("echo 0x00 > bDeviceClass\n");
        builder.append("echo 0x00 > bDeviceSubClass\n");
        builder.append("echo 0x00 > bDeviceProtocol\n");
        builder.append("echo 64 > bMaxPacketSize0\n");
        builder.append("dump_file \"$GADGET_PATH/max_speed\"\n");
        builder.append("dump_file \"$GADGET_PATH/bcdUSB\"\n");
        builder.append("dump_file \"$GADGET_PATH/bDeviceClass\"\n");
        builder.append("dump_file \"$GADGET_PATH/bDeviceSubClass\"\n");
        builder.append("dump_file \"$GADGET_PATH/bDeviceProtocol\"\n");
        builder.append("dump_file \"$GADGET_PATH/bMaxPacketSize0\"\n");
        builder.append("log \"configure keyboard function\"\n");
        builder.append("mkdir -p \"$GADGET_PATH/functions/hid.keyboard\"\n");
        builder.append("cd \"$GADGET_PATH/functions/hid.keyboard\"\n");
        builder.append("echo 1 > protocol\n");
        builder.append("echo 1 > subclass\n");
        builder.append("echo 8 > report_length\n");
        builder.append("echo -ne '").append(KEYBOARD_DESCRIPTOR).append("' > report_desc\n");
        builder.append("dump_file \"$GADGET_PATH/functions/hid.keyboard/protocol\"\n");
        builder.append("dump_file \"$GADGET_PATH/functions/hid.keyboard/subclass\"\n");
        builder.append("dump_file \"$GADGET_PATH/functions/hid.keyboard/report_length\"\n");
        builder.append("log \"configure mouse function length=").append(mouseReportLength)
                .append(" mode=").append(mouseMode.name()).append("\"\n");
        builder.append("mkdir -p \"$GADGET_PATH/functions/hid.mouse\"\n");
        builder.append("cd \"$GADGET_PATH/functions/hid.mouse\"\n");
        builder.append("echo 2 > protocol\n");
        builder.append("echo 1 > subclass\n");
        builder.append("echo ").append(mouseReportLength).append(" > report_length\n");
        builder.append("echo -ne '").append(mouseDescriptor).append("' > report_desc\n");
        builder.append("dump_file \"$GADGET_PATH/functions/hid.mouse/protocol\"\n");
        builder.append("dump_file \"$GADGET_PATH/functions/hid.mouse/subclass\"\n");
        builder.append("dump_file \"$GADGET_PATH/functions/hid.mouse/report_length\"\n");
        builder.append("log \"write device ids and strings\"\n");
        builder.append("cd \"$GADGET_PATH\"\n");
        builder.append("echo ").append(escapeShell(vendorId)).append(" > idVendor\n");
        builder.append("echo ").append(escapeShell(productId)).append(" > idProduct\n");
        builder.append("cd \"$STRINGS_PATH\"\n");
        builder.append("echo '").append(escapeShell(manufacturer)).append("' > manufacturer\n");
        builder.append("echo '").append(escapeShell(productName)).append("' > product\n");
        builder.append("echo '").append(escapeShell(serial)).append("' > serialnumber\n");
        builder.append("cd \"$CONFIG_PATH\"\n");
        builder.append("echo 0x80 > bmAttributes\n");
        builder.append("echo 0 > MaxPower\n");
        builder.append("mkdir -p \"$CONFIG_PATH/strings/0x409\"\n");
        builder.append("echo 'Configuration' > \"$CONFIG_PATH/strings/0x409/configuration\"\n");
        builder.append("dump_file \"$GADGET_PATH/idVendor\"\n");
        builder.append("dump_file \"$GADGET_PATH/idProduct\"\n");
        builder.append("dump_file \"$STRINGS_PATH/manufacturer\"\n");
        builder.append("dump_file \"$STRINGS_PATH/product\"\n");
        builder.append("dump_file \"$STRINGS_PATH/serialnumber\"\n");
        builder.append("dump_file \"$CONFIG_PATH/bmAttributes\"\n");
        builder.append("dump_file \"$CONFIG_PATH/MaxPower\"\n");
        builder.append("log \"link functions into config\"\n");
        builder.append("rm -f \"$CONFIG_PATH/hid.keyboard\" \"$CONFIG_PATH/hid.mouse\"\n");
        builder.append("ln -s \"$GADGET_PATH/functions/hid.keyboard\" \"$CONFIG_PATH/hid.keyboard\"\n");
        builder.append("ln -s \"$GADGET_PATH/functions/hid.mouse\" \"$CONFIG_PATH/hid.mouse\"\n");
        builder.append("ls -l \"$CONFIG_PATH\"\n");
        if (bindUdc) {
            builder.append("log \"unbind other gadgets\"\n");
            builder.append("find \"$GADGETS_PATH\" -name UDC -type f -exec sh -c 'echo \"\" > \"$1\" || true' _ {} \\;\n");
            builder.append("log \"detect UDC\"\n");
            builder.append("UDC_NAME=\"$(getprop sys.usb.controller)\"\n");
            builder.append("if [ -z \"$UDC_NAME\" ] && [ -d /sys/class/udc ]; then UDC_NAME=\"$(ls /sys/class/udc | head -n 1)\"; fi\n");
            builder.append("[ -n \"$UDC_NAME\" ] || { echo \"UDC not found\"; exit 1; }\n");
            builder.append("log \"bind UDC=$UDC_NAME\"\n");
            builder.append("echo \"$UDC_NAME\" > \"$GADGET_PATH/UDC\"\n");
            builder.append("dump_file \"$GADGET_PATH/UDC\"\n");
        }
        builder.append("log \"done gadget_path=$GADGET_PATH\"\n");
        return builder.toString();
    }

    @NonNull
    private String buildRestoreSystemShellScript(@NonNull String gadgetName) {
        StringBuilder builder = new StringBuilder();
        builder.append("set -e\n");
        builder.append("PS4='[sh] '\n");
        builder.append("set -x\n");
        builder.append("log(){ echo \"[usb-gadget] $*\"; }\n");
        builder.append("dump_file(){ if [ -e \"$1\" ]; then echo \"[dump] $1=$(cat \"$1\" 2>/dev/null)\"; else echo \"[dump] missing:$1\"; fi; }\n");
        builder.append("CONFIGFS_DIR=\"/config\"\n");
        builder.append("GADGETS_PATH=\"${CONFIGFS_DIR}/usb_gadget\"\n");
        builder.append("[ -d \"$GADGETS_PATH\" ] || { echo \"configfs usb_gadget not found at /config/usb_gadget\"; exit 1; }\n");
        builder.append("CURRENT_GADGET=\"$GADGETS_PATH/").append(escapeShell(gadgetName)).append("\"\n");
        builder.append("UDC_NAME=\"\"\n");
        builder.append("if [ -f \"$CURRENT_GADGET/UDC\" ]; then UDC_NAME=\"$(cat \"$CURRENT_GADGET/UDC\")\"; fi\n");
        builder.append("if [ -z \"$UDC_NAME\" ]; then UDC_NAME=\"$(getprop sys.usb.controller)\"; fi\n");
        builder.append("if [ -z \"$UDC_NAME\" ] && [ -d /sys/class/udc ]; then UDC_NAME=\"$(ls /sys/class/udc | head -n 1)\"; fi\n");
        builder.append("[ -n \"$UDC_NAME\" ] || { echo \"UDC not found\"; exit 1; }\n");
        builder.append("log \"restore using UDC=$UDC_NAME\"\n");
        builder.append("if [ -f \"$CURRENT_GADGET/UDC\" ]; then\n");
        builder.append("  log \"detach current gadget ").append(escapeShell(gadgetName)).append("\"\n");
        builder.append("  echo \"\" > \"$CURRENT_GADGET/UDC\" || true\n");
        builder.append("  dump_file \"$CURRENT_GADGET/UDC\"\n");
        builder.append("fi\n");
        builder.append("TARGET_GADGET=\"\"\n");
        builder.append("for candidate in g1 g2; do\n");
        builder.append("  if [ -d \"$GADGETS_PATH/$candidate\" ]; then TARGET_GADGET=\"$GADGETS_PATH/$candidate\"; break; fi\n");
        builder.append("done\n");
        builder.append("if [ -z \"$TARGET_GADGET\" ]; then\n");
        builder.append("  for dir in \"$GADGETS_PATH\"/*; do\n");
        builder.append("    [ -d \"$dir\" ] || continue\n");
        builder.append("    [ \"$dir\" = \"$CURRENT_GADGET\" ] && continue\n");
        builder.append("    TARGET_GADGET=\"$dir\"\n");
        builder.append("    break\n");
        builder.append("  done\n");
        builder.append("fi\n");
        builder.append("[ -n \"$TARGET_GADGET\" ] || { echo \"system gadget not found\"; exit 1; }\n");
        builder.append("log \"target system gadget=$TARGET_GADGET\"\n");
        builder.append("find \"$GADGETS_PATH\" -name UDC -type f -exec sh -c 'echo \"\" > \"$1\" || true' _ {} \\;\n");
        builder.append("echo \"$UDC_NAME\" > \"$TARGET_GADGET/UDC\"\n");
        builder.append("dump_file \"$TARGET_GADGET/UDC\"\n");
        builder.append("if [ -d \"$TARGET_GADGET/configs\" ]; then find \"$TARGET_GADGET/configs\" -maxdepth 2 -type l -ls; fi\n");
        builder.append("log \"restore done target=$TARGET_GADGET\"\n");
        return builder.toString();
    }

    @NonNull
    private String sanitizeGadgetName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replaceAll("[^A-Za-z0-9._-]", "");
    }

    @NonNull
    private String normalizeHexId(String raw, @NonNull String fallback) {
        if (raw == null) {
            return fallback;
        }
        String cleaned = raw.trim().toLowerCase(Locale.US);
        if (cleaned.isEmpty()) {
            return fallback;
        }
        if (!cleaned.startsWith("0x")) {
            cleaned = "0x" + cleaned;
        }
        if (!cleaned.matches("0x[0-9a-f]{4}")) {
            return fallback;
        }
        return cleaned;
    }

    @NonNull
    private String fallback(String value, @NonNull String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    @NonNull
    private String getText(@NonNull TextInputEditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    private void setMouseModeSelection(@NonNull String target) {
        for (int i = 0; i < mouseModeSpinner.getCount(); i++) {
            Object value = mouseModeSpinner.getItemAtPosition(i);
            if (target.equalsIgnoreCase(String.valueOf(value))) {
                mouseModeSpinner.setSelection(i);
                return;
            }
        }
        mouseModeSpinner.setSelection(0);
    }

    private void savePreferences(
            @NonNull String gadgetName,
            @NonNull String vendorId,
            @NonNull String productId,
            @NonNull String manufacturer,
            @NonNull String productName,
            @NonNull String serial,
            @NonNull HidMouseMode mouseMode,
            boolean bindUdc
    ) {
        preferences.edit()
                .putString(PREF_GADGET_NAME, gadgetName)
                .putString(PREF_GADGET_VENDOR_ID, vendorId)
                .putString(PREF_GADGET_PRODUCT_ID, productId)
                .putString(PREF_GADGET_MANUFACTURER, manufacturer)
                .putString(PREF_GADGET_PRODUCT_NAME, productName)
                .putString(PREF_GADGET_SERIAL, serial)
                .putString(PREF_HID_MOUSE_MODE, mouseMode.name())
                .putBoolean(PREF_GADGET_BIND_UDC, bindUdc)
                .apply();
    }

    @NonNull
    private String escapeShell(@NonNull String value) {
        return value.replace("\\", "\\\\").replace("'", "'\"'\"'");
    }

    @NonNull
    private String buildResultLog(@NonNull Shell.Result result) {
        StringBuilder builder = new StringBuilder();
        appendSection(builder, "stdout", result.getOut());
        appendSection(builder, "stderr", result.getErr());
        String merged = builder.toString().trim();
        if (merged.isEmpty()) {
            return getString(R.string.gadget_status_no_log);
        }
        return merged;
    }

    @NonNull
    private String summarizeFailure(@NonNull Shell.Result result, @NonNull String logOutput) {
        if (!result.getErr().isEmpty()) {
            return logOutput;
        }
        if (!result.getOut().isEmpty()) {
            return logOutput;
        }
        return getString(R.string.gadget_status_no_log);
    }

    private void appendSection(@NonNull StringBuilder builder, @NonNull String title, @NonNull List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n').append('\n');
        }
        builder.append('[').append(title).append(']').append('\n');
        appendLines(builder, lines);
    }

    private void appendLines(@NonNull StringBuilder builder, @NonNull List<String> lines) {
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                builder.append('\n');
            }
            builder.append(line.trim());
            builder.append('\n');
        }
    }

    private void updateStatus(@NonNull String message) {
        statusText.setText(message);
    }
}
