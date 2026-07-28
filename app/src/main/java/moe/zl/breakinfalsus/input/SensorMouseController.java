package moe.zl.breakinfalsus.input;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.annotation.NonNull;

public class SensorMouseController implements SensorEventListener {

    public interface MouseSignalListener {
        void onAccelerometerValue(float value, int hidDelta);

        void onGyroscopeValue(float value, int hidDelta);

        void onGravityValue(float value, int hidDelta);
    }

    public enum Mode {
        ACCELEROMETER,
        GYROSCOPE,
        GRAVITY
    }

    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Sensor gyroscope;
    private final Sensor gravity;
    private final MouseSignalListener listener;

    private Mode mode = Mode.GRAVITY;
    private float sensitivity = 20f;
    private float deadzone = 0.01f;
    private boolean started;
    private float latestAccelerometerValue;
    private float accelerometerZero;
    private float accelerometerRemainder;
    private float gyroscopeRemainder;
    private float gravityRemainder;

    public SensorMouseController(@NonNull Context context, @NonNull MouseSignalListener listener) {
        this.listener = listener;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        gravity = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
    }

    public void setMode(@NonNull Mode mode) {
        this.mode = mode;
        restartIfNeeded();
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    public void setDeadzone(float deadzone) {
        this.deadzone = deadzone;
    }

    public void setAccelerometerZero(float accelerometerZero) {
        this.accelerometerZero = accelerometerZero;
    }

    public float getAccelerometerZero() {
        return accelerometerZero;
    }

    public float calibrateAccelerometerZero() {
        accelerometerZero = latestAccelerometerValue;
        accelerometerRemainder = 0f;
        return accelerometerZero;
    }

    public void start() {
        if (started || sensorManager == null) {
            return;
        }
        started = true;
        registerCurrentSensor();
    }

    public void stop() {
        if (!started || sensorManager == null) {
            return;
        }
        started = false;
        sensorManager.unregisterListener(this);
        accelerometerRemainder = 0f;
        gyroscopeRemainder = 0f;
        gravityRemainder = 0f;
    }

    private void restartIfNeeded() {
        if (!started || sensorManager == null) {
            return;
        }
        sensorManager.unregisterListener(this);
        registerCurrentSensor();
    }

    private void registerCurrentSensor() {
        Sensor sensor;
        if (mode == Mode.ACCELEROMETER) {
            sensor = accelerometer;
        } else if (mode == Mode.GYROSCOPE) {
            sensor = gyroscope;
        } else {
            sensor = gravity;
        }
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (mode == Mode.ACCELEROMETER && event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            latestAccelerometerValue = event.values[1];
            float adjustedValue = latestAccelerometerValue - accelerometerZero;
            if (Math.abs(adjustedValue) < deadzone) {
                adjustedValue = 0f;
            }
            int hidDelta = clampToMouseDelta(consumeAccelerometerDelta(adjustedValue * sensitivity));
            listener.onAccelerometerValue(latestAccelerometerValue, hidDelta);
        } else if (mode == Mode.GYROSCOPE && event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            float zValue = event.values[2];
            if (Math.abs(zValue) < deadzone) {
                zValue = 0f;
            }
            int hidDelta = clampToMouseDelta(consumeGyroscopeDelta(zValue * sensitivity));
            listener.onGyroscopeValue(event.values[2], hidDelta);
        } else if (mode == Mode.GRAVITY && event.sensor.getType() == Sensor.TYPE_GRAVITY) {
            float xValue = event.values[0];
            float yValue = event.values[1];
            float angle = (float) Math.atan2(yValue, xValue);
            if (Math.abs(angle) < deadzone) {
                angle = 0f;
            }
            int hidDelta = clampToMouseDelta(consumeGravityDelta(angle * sensitivity));
            listener.onGravityValue(angle, hidDelta);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No-op.
    }

    private int clampToMouseDelta(int value) {
        return Math.max(-127, Math.min(127, value));
    }

    private int consumeAccelerometerDelta(float value) {
        float total = value + accelerometerRemainder;
        int delta = (int) total;
        accelerometerRemainder = total - delta;
        return delta;
    }

    private int consumeGyroscopeDelta(float value) {
        float total = value + gyroscopeRemainder;
        int delta = (int) total;
        gyroscopeRemainder = total - delta;
        return delta;
    }

    private int consumeGravityDelta(float value) {
        float total = value + gravityRemainder;
        int delta = (int) total;
        gravityRemainder = total - delta;
        return delta;
    }
}
