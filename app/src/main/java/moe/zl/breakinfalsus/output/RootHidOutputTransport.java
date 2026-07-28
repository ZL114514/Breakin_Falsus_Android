package moe.zl.breakinfalsus.output;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.io.SuFile;
import com.topjohnwu.superuser.io.SuFileOutputStream;

import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class RootHidOutputTransport extends OutputTransport {
    private static final int ABSOLUTE_AXIS_MAX = 0x7fff;
    private static final int ABSOLUTE_AXIS_MIDPOINT = ABSOLUTE_AXIS_MAX / 2;
    private static final float ABSOLUTE_GRAVITY_ANGLE_LIMIT = (float) (Math.PI / 2.0);

    private static final byte[] KEY_CODES = new byte[]{
            0x00,
            0x04,
            0x16,
            0x07,
            0x09,
            0x2c
    };

    private final String keyboardPath;
    private final String mousePath;
    private final HidMouseMode mouseMode;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final AtomicInteger pendingMouseDeltaX = new AtomicInteger();
    private final AtomicInteger pendingMouseDeltaY = new AtomicInteger();
    private final AtomicBoolean mouseDispatchScheduled = new AtomicBoolean(false);
    private int absoluteX = ABSOLUTE_AXIS_MIDPOINT;
    private int absoluteY = ABSOLUTE_AXIS_MIDPOINT;
    private OutputStream keyboardStream;
    private OutputStream mouseStream;
    private volatile boolean closed;

    public RootHidOutputTransport(String keyboardPath, String mousePath) {
        this(keyboardPath, mousePath, HidMouseMode.RELATIVE);
    }

    public RootHidOutputTransport(String keyboardPath, String mousePath, HidMouseMode mouseMode) {
        this.keyboardPath = keyboardPath;
        this.mousePath = mousePath;
        this.mouseMode = mouseMode;
        Shell.getShell();
    }

    @Override
    public void sendKeyboardState(boolean[] keyStates) {
        if (keyStates == null || keyStates.length != 6) {
            return;
        }
        byte[] report = new byte[8];
        if (keyStates[0]) {
            report[0] = 0x02;
        }
        int keySlot = 2;
        for (int i = 1; i < keyStates.length && keySlot < report.length; i++) {
            if (keyStates[i]) {
                report[keySlot++] = KEY_CODES[i];
            }
        }
        enqueueKeyboardReport(report);
    }

    @Override
    public void sendAccelerometer(float value) {
        // Root HID absolute mode is driven by gravity values only.
    }

    @Override
    public void sendGyroscope(float value) {
        // Root HID absolute mode is driven by gravity values only.
    }

    @Override
    public void sendGravity(float value) {
        if (closed || mouseMode != HidMouseMode.ABSOLUTE) {
            return;
        }
        int mappedX = mapGravityToAbsoluteAxis(value);
        enqueue(() -> {
            absoluteX = mappedX;
            absoluteY = ABSOLUTE_AXIS_MIDPOINT;
            writeMouseReport(buildAbsoluteMouseReport());
        });
    }

    @Override
    public void sendMouseMove(int deltaX, int deltaY) {
        if (closed || mouseMode != HidMouseMode.RELATIVE) {
            return;
        }
        pendingMouseDeltaX.addAndGet(deltaX);
        pendingMouseDeltaY.addAndGet(deltaY);
        scheduleMouseDispatch();
    }

    @Override
    public void sendPauseToggle() {
        enqueue(() -> {
            byte[] pressed = new byte[8];
            pressed[2] = 0x29;
            writeKeyboardReport(pressed);
            writeKeyboardReport(new byte[8]);
        });
    }

    @Override
    public void sendAccelerometerCalibration(float zeroG) {
        // Calibration is applied on-device before HID mouse motion is emitted.
    }

    @Override
    public boolean supportsReset() {
        return mouseMode == HidMouseMode.ABSOLUTE;
    }

    @Override
    public void sendReset() {
        if (mouseMode != HidMouseMode.ABSOLUTE) {
            return;
        }
        enqueue(() -> {
            absoluteX = ABSOLUTE_AXIS_MIDPOINT;
            absoluteY = ABSOLUTE_AXIS_MIDPOINT;
            writeMouseReport(buildAbsoluteMouseReport());
        });
    }

    @Override
    public void close() {
        closed = true;
        try {
            executorService.execute(() -> {
                writeKeyboardReport(new byte[8]);
                if (mouseMode == HidMouseMode.RELATIVE) {
                    writeMouseReport(new byte[4]);
                }
                closeStream(keyboardStream);
                closeStream(mouseStream);
                keyboardStream = null;
                mouseStream = null;
            });
        } catch (Exception ignored) {
            writeKeyboardReport(new byte[8]);
            closeStream(keyboardStream);
            closeStream(mouseStream);
            keyboardStream = null;
            mouseStream = null;
        }
        executorService.shutdown();
    }

    private int clampAbsolute(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, ABSOLUTE_AXIS_MAX);
    }

    private int mapGravityToAbsoluteAxis(float angle) {
        float normalized = angle / ABSOLUTE_GRAVITY_ANGLE_LIMIT;
        normalized = Math.max(-1f, Math.min(1f, normalized));
        return clampAbsolute(Math.round(((normalized + 1f) * 0.5f) * ABSOLUTE_AXIS_MAX));
    }

    private void enqueueKeyboardReport(byte[] report) {
        byte[] snapshot = Arrays.copyOf(report, report.length);
        enqueue(() -> writeKeyboardReport(snapshot));
    }

    private void scheduleMouseDispatch() {
        if (!mouseDispatchScheduled.compareAndSet(false, true)) {
            return;
        }
        enqueue(() -> {
            try {
                flushPendingMouseMove();
            } finally {
                mouseDispatchScheduled.set(false);
                if (pendingMouseDeltaX.get() != 0 || pendingMouseDeltaY.get() != 0) {
                    scheduleMouseDispatch();
                }
            }
        });
    }

    private void flushPendingMouseMove() {
        int deltaX = pendingMouseDeltaX.getAndSet(0);
        int deltaY = pendingMouseDeltaY.getAndSet(0);
        if (deltaX == 0 && deltaY == 0) {
            return;
        }
        int remainingX = deltaX;
        int remainingY = deltaY;
        while (remainingX != 0 || remainingY != 0) {
            int stepX = clampRelativeAxis(remainingX);
            int stepY = clampRelativeAxis(remainingY);
            writeMouseReport(new byte[]{
                    0x00,
                    (byte) stepX,
                    (byte) stepY,
                    0x00
            });
            remainingX -= stepX;
            remainingY -= stepY;
        }
    }

    private byte[] buildAbsoluteMouseReport() {
        return new byte[]{
                0x00,
                (byte) (absoluteX & 0xff),
                (byte) ((absoluteX >> 8) & 0xff),
                (byte) (absoluteY & 0xff),
                (byte) ((absoluteY >> 8) & 0xff)
        };
    }
    private int clampRelativeAxis(int value) {
        if (value > 127) {
            return 127;
        }
        if (value < -127) {
            return -127;
        }
        return value;
    }

    private void enqueue(Runnable runnable) {
        if (closed) {
            return;
        }
        try {
            executorService.execute(runnable);
        } catch (Exception ignored) {
            // Transport is closing or executor rejected the task.
        }
    }

    private void writeKeyboardReport(byte[] report) {
        writeReport(true, report);
    }

    private void writeMouseReport(byte[] report) {
        writeReport(false, report);
    }

    private void writeReport(boolean keyboard, byte[] report) {
        try {
            OutputStream outputStream = keyboard ? keyboardStream : mouseStream;
            String path = keyboard ? keyboardPath : mousePath;
            SuFile file = new SuFile(path);
            if (!file.exists()) {
                return;
            }
            if (outputStream == null) {
                outputStream = SuFileOutputStream.open(file);
                if (keyboard) {
                    keyboardStream = outputStream;
                } else {
                    mouseStream = outputStream;
                }
            }
            outputStream.write(report);
            outputStream.flush();
        } catch (Exception ignored) {
            if (keyboard) {
                closeStream(keyboardStream);
                keyboardStream = null;
            } else {
                closeStream(mouseStream);
                mouseStream = null;
            }
        }
    }

    private void closeStream(OutputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Exception ignored) {
        }
    }
}
