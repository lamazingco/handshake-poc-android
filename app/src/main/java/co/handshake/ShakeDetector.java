package co.handshake;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class ShakeDetector implements SensorEventListener {

    public static final int SENSITIVITY_LIGHT = 11;
    public static final int SENSITIVITY_MEDIUM = 13;
    public static final int SENSITIVITY_HARD = 15;

    private static final int SENSOR_UPDATE_INTERVAL_MICROSECONDS = 100000;
    private static final int DEFAULT_ACCELERATION_THRESHOLD = SENSITIVITY_LIGHT;

    /**
     * When the magnitude of total acceleration exceeds this
     * value, the phone is accelerating.
     */
    private int accelerationThreshold = DEFAULT_ACCELERATION_THRESHOLD;

    /** Listens for shakes. */
    public interface Listener {
        /** Called on the main thread when the device is shaken. */
        void hearShake();
    }

    private final SampleQueue queue = new ShakeDetector.SampleQueue();
    private final ShakeDetector.Listener listener;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    public ShakeDetector(ShakeDetector.Listener listener) {
        this.listener = listener;
    }

    /**
     * Starts listening for shakes on devices with appropriate hardware.
     *
     * @return true if the device supports shake detection.
     */
    public boolean start(SensorManager sensorManager) {
        // Already started?
        if (accelerometer != null) {
            return true;
        }

        accelerometer = sensorManager.getDefaultSensor(
                Sensor.TYPE_ACCELEROMETER);

        // If this phone has an accelerometer, listen to it.
        if (accelerometer != null) {
            this.sensorManager = sensorManager;
            sensorManager.registerListener(this, accelerometer,
                    SENSOR_UPDATE_INTERVAL_MICROSECONDS);
        }
        return accelerometer != null;
    }

    /**
     * Stops listening.  Safe to call when already stopped.  Ignored on devices
     * without appropriate hardware.
     */
    public void stop() {
        if (accelerometer != null) {
            sensorManager.unregisterListener(this, accelerometer);
            sensorManager = null;
            accelerometer = null;
        }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        boolean accelerating = isAccelerating(event);
        long timestamp = event.timestamp;
        logData(event);
        queue.add(timestamp, accelerating, event);
        if (queue.isShaking()) {
            queue.clear();
            //Log.d("onShake detected", "timestamp ns:" + event.timestamp);
            listener.hearShake();
        }
    }

    private void logData(SensorEvent event) {
        if (queue.newest == null) {
            return;
        }

        float ax = event.values[0];
        float ay = event.values[1];
        float az = event.values[2];

        Log.d("onSensorChanged","timestamp:" + System.currentTimeMillis()/100 + " ax=" + ax + " ay=" + ay + " az=" + az);
    }

    /** Returns true if the device is currently accelerating. */
    private boolean isAccelerating(SensorEvent event) {
        float ax = event.values[0];
        float ay = event.values[1];
        float az = event.values[2];

        // Instead of comparing magnitude to ACCELERATION_THRESHOLD,
        // compare their squares. This is equivalent and doesn't need the
        // actual magnitude, which would be computed using (expesive) Math.sqrt().
        final double magnitudeSquared = ax * ax + ay * ay + az * az;
        return magnitudeSquared > accelerationThreshold * accelerationThreshold;
    }

    /** Sets the acceleration threshold sensitivity. */
    public void setSensitivity(int accelerationThreshold) {
        this.accelerationThreshold = accelerationThreshold;
    }

    /** Queue of samples. Keeps a running average. */
    static class SampleQueue {

        /** Window size in ns. Used to compute the average. */
        private static final long MAX_WINDOW_SIZE = 500000000; // 0.5s
        private static final long MIN_WINDOW_SIZE = MAX_WINDOW_SIZE >> 1; // 0.25s

        /**
         * Ensure the queue size never falls below this size, even if the device
         * fails to deliver this many events during the time window. The LG Ally
         * is one such device.
         */
        private static final int MIN_QUEUE_SIZE = 4;

        private final ShakeDetector.SamplePool pool = new ShakeDetector.SamplePool();

        private ShakeDetector.Sample oldest;
        private ShakeDetector.Sample newest;
        private int sampleCount;
        private int acceleratingCount;

        /**
         * Adds a sample.
         *
         * @param timestamp    in nanoseconds of sample
         * @param accelerating true if > {@link #accelerationThreshold}.
         */
        void add(long timestamp, boolean accelerating, SensorEvent event) {
            // Purge samples that proceed window.
            purge(timestamp - MAX_WINDOW_SIZE);

            // Add the sample to the queue.
            ShakeDetector.Sample added = pool.acquire();
            added.timestamp = timestamp;
            added.accelerating = accelerating;
            added.ax = event.values[0];
            added.ay = event.values[1];
            added.az = event.values[2];

            added.next = null;
            if (newest != null) {
                newest.next = added;
            }
            newest = added;
            if (oldest == null) {
                oldest = added;
            }

            // Update running average.
            sampleCount++;
            if (accelerating) {
                acceleratingCount++;
            }
        }

        /** Removes all samples from this queue. */
        void clear() {
            while (oldest != null) {
                ShakeDetector.Sample removed = oldest;
                oldest = removed.next;
                pool.release(removed);
            }
            newest = null;
            sampleCount = 0;
            acceleratingCount = 0;
        }

        /** Purges samples with timestamps older than cutoff. */
        void purge(long cutoff) {
            while (sampleCount >= MIN_QUEUE_SIZE
                    && oldest != null && cutoff - oldest.timestamp > 0) {
                // Remove sample.
                ShakeDetector.Sample removed = oldest;
                if (removed.accelerating) {
                    acceleratingCount--;
                }
                sampleCount--;

                oldest = removed.next;
                if (oldest == null) {
                    newest = null;
                }
                pool.release(removed);
            }
        }

        /** Copies the samples into a list, with the oldest entry at index 0. */
        List<ShakeDetector.Sample> asList() {
            List<ShakeDetector.Sample> list = new ArrayList<ShakeDetector.Sample>();
            ShakeDetector.Sample s = oldest;
            while (s != null) {
                list.add(s);
                s = s.next;
            }
            return list;
        }

        /**
         * Returns true if we have enough samples and more than 3/4 of those samples
         * are accelerating.
         */
        boolean isShaking() {
            return newest != null
                    && oldest != null
                    && newest.timestamp - oldest.timestamp >= MIN_WINDOW_SIZE
                    && acceleratingCount >= (sampleCount >> 1) + (sampleCount >> 2);
        }
    }

    /** An accelerometer sample. */
    static class Sample {
        /** Time sample was taken. */
        long timestamp;

        /** If acceleration > {@link #accelerationThreshold}. */
        boolean accelerating;

        float ax, ay, az;

        /** Next sample in the queue or pool. */
        ShakeDetector.Sample next;
    }

    /** Pools samples. Avoids garbage collection. */
    static class SamplePool {
        private ShakeDetector.Sample head;

        /** Acquires a sample from the pool. */
        ShakeDetector.Sample acquire() {
            ShakeDetector.Sample acquired = head;
            if (acquired == null) {
                acquired = new ShakeDetector.Sample();
            } else {
                // Remove instance from pool.
                head = acquired.next;
            }
            return acquired;
        }

        /** Returns a sample to the pool. */
        void release(ShakeDetector.Sample sample) {
            sample.next = head;
            head = sample;
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}



