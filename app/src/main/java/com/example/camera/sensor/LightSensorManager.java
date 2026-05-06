package com.example.camera.sensor;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.camera.R;

/**
 * 环境光传感器封装，用于 v2.0 真实亮度标定（lux → 视亮度 cd/m²）。
 * <p>
 * 权限说明：{@link Sensor#TYPE_LIGHT} 为设备内置光线传感器，读取照度不需要
 * {@code android.permission.*}；仅需通过 {@link Context#SENSOR_SERVICE} 获取
 * {@link SensorManager}（与 minSdk 24 及以上系统行为一致）。
 * <p>
 * UI 更新：传感器底层频率可能很高，{@link #getAmbientLightLiveData()} 与
 * {@link OnLightSensorChangedListener} 均经过 {@link #DEFAULT_UI_THROTTLE_MS} 节流，避免主线程积压。
 * 业务层（如标定）通过 LiveData/回调收到的已是节流后的最新快照。
 * <p>
 * 生命周期：请在 {@code onPause} / {@code onDestroy} 中调用 {@link #stopListening()}，
 * 或使用 {@link #bindToLifecycle(LifecycleOwner)} 在暂停/销毁时自动取消注册，避免后台耗电。
 */
public final class LightSensorManager {

    /** 向 UI / LiveData 推送的最小间隔（ms），减轻高频回调导致的卡顿。 */
    public static final long DEFAULT_UI_THROTTLE_MS = 300L;

    /**
     * 光线传感器不可用时的细分原因，便于界面提示降级。
     */
    public enum Availability {
        /** {@link SensorManager#getDefaultSensor(int)} 可拿到 TYPE_LIGHT。 */
        AVAILABLE,
        /** 系统未声明 {@link PackageManager#FEATURE_SENSOR_LIGHT}，且当前也无可用传感器实例。 */
        NO_SYSTEM_FEATURE,
        /** 系统声明支持但 {@link SensorManager#getDefaultSensor(int)} 为 null（厂商裁剪、驱动未暴露、模拟器等）。 */
        NO_HARDWARE_DRIVER
    }

    /**
     * 光线传感器数值变化回调（与 LiveData 同源、同节流策略）。运行在主线程。
     */
    public interface OnLightSensorChangedListener {
        void onLightChanged(float lux, double luminanceCdM2);
    }

    private static volatile LightSensorManager instance;

    private final Context appContext;
    private final SensorManager sensorManager;
    private final SensorCalibrationStore sensorCalibrationStore;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<AmbientLightReading> ambientLiveData = new MutableLiveData<>();

    private final SensorEventListener sensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(@NonNull SensorEvent event) {
            if (event.sensor.getType() != Sensor.TYPE_LIGHT) {
                return;
            }
            float rawLux = event.values[0];
            if (!Float.isFinite(rawLux)) {
                return;
            }
            lastRawLux = rawLux;
            float calLux = sensorCalibrationStore.applyToLux(rawLux);
            double luminanceCdM2 = computeLuminanceCdM2(calLux);
            if (!Double.isFinite(luminanceCdM2)) {
                return;
            }
            pendingRawLux = rawLux;
            pendingValid = true;

            long now = SystemClock.elapsedRealtime();
            if (now - lastThrottleEmitElapsedMs >= uiThrottleMs) {
                lastThrottleEmitElapsedMs = now;
                dispatchThrottled(rawLux, calLux, luminanceCdM2, now);
            }
        }

        @Override
        public void onAccuracyChanged(@NonNull Sensor sensor, int accuracy) {
            // 标定场景通常不依赖 accuracy；如需可根据 accuracy 过滤数据可在此扩展
        }
    };

    private final DefaultLifecycleObserver lifecycleAutoStop = new DefaultLifecycleObserver() {
        @Override
        public void onPause(@NonNull LifecycleOwner owner) {
            stopListening();
        }

        @Override
        public void onDestroy(@NonNull LifecycleOwner owner) {
            stopListening();
            owner.getLifecycle().removeObserver(this);
            boundLifecycleOwner = null;
        }
    };

    private long uiThrottleMs = DEFAULT_UI_THROTTLE_MS;
    private long lastThrottleEmitElapsedMs = 0L;
    private volatile float pendingRawLux = Float.NaN;
    private volatile boolean pendingValid;
    /** 最近一次 {@link SensorEvent} 的原始 lux，未节流。 */
    private volatile float lastRawLux = Float.NaN;

    @Nullable
    private OnLightSensorChangedListener listener;

    @Nullable
    private LifecycleOwner boundLifecycleOwner;

    private boolean listening;

    /**
     * 使用应用级 {@link Context}，避免持有 Activity 泄漏。
     */
    public LightSensorManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        this.sensorCalibrationStore = new SensorCalibrationStore(appContext);
    }

    /**
     * 未节流的原始 lux；无有效事件前为 NaN。
     */
    public float getRawLux() {
        return lastRawLux;
    }

    /**
     * 与 {@link #getRawLux()} 相同，保留旧名以兼容调用方。
     */
    public float getLastRawLux() {
        return getRawLux();
    }

    /**
     * 当前（最近一次采样）经 lux 校准后的值；无有效原始读数时为 NaN。
     */
    public float getCurrentLux() {
        return sensorCalibrationStore.applyToLux(lastRawLux);
    }

    /**
     * App 调试公式：lux×0.18/π（cd/m²），与调试 CSV「App计算亮度」列一致。
     * 传入的 lux 应为 {@link #getCurrentLux()} 或已等效校准后的 lux。
     */
    public static double appDebugLuminanceFromLux(double lux) {
        if (!Double.isFinite(lux)) {
            return Double.NaN;
        }
        return lux * 0.18 / Math.PI;
    }

    private double computeLuminanceCdM2(float calibratedLux) {
        return appDebugLuminanceFromLux(calibratedLux);
    }

    /**
     * 可选单例；首次调用后固定使用第一次传入的 {@link Context} 对应的应用上下文。
     */
    @NonNull
    public static LightSensorManager getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (LightSensorManager.class) {
                if (instance == null) {
                    instance = new LightSensorManager(context);
                }
            }
        }
        return instance;
    }

    /**
     * 供 MVP/MVVM：界面观察此 LiveData，在主线程序列化收到节流后的读数（可能为 null 直至首次采样）。
     */
    @NonNull
    public LiveData<AmbientLightReading> getAmbientLightLiveData() {
        return ambientLiveData;
    }

    /**
     * 设置向 UI 层合并推送的最小间隔；需在 {@link #startListening} 前修改方可保证整段监听一致。
     */
    public void setUiThrottleMs(long uiThrottleMs) {
        this.uiThrottleMs = Math.max(50L, uiThrottleMs);
    }

    public long getUiThrottleMs() {
        return uiThrottleMs;
    }

    /**
     * 细分不可用原因；{@link #hasLightSensor()} 等价于 {@code getAvailability() == Availability.AVAILABLE}。
     * <p>
     * 判定顺序：优先以 {@link SensorManager#getDefaultSensor(int)} 为准（有实例即可用），
     * 否则再结合 {@link PackageManager#FEATURE_SENSOR_LIGHT} 区分提示文案。
     */
    @NonNull
    public Availability getAvailability() {
        if (sensorManager == null) {
            return Availability.NO_HARDWARE_DRIVER;
        }
        if (sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null) {
            return Availability.AVAILABLE;
        }
        PackageManager pm = appContext.getPackageManager();
        if (pm != null && !pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_LIGHT)) {
            return Availability.NO_SYSTEM_FEATURE;
        }
        return Availability.NO_HARDWARE_DRIVER;
    }

    /**
     * 设备是否具备可用的环境光传感器实例。
     */
    public boolean hasLightSensor() {
        return getAvailability() == Availability.AVAILABLE;
    }

    /**
     * 不可用时的简短说明（供 TextView 展示）；{@link Availability#AVAILABLE} 返回 null。
     */
    @Nullable
    public String getUnavailableDisplayMessage(@NonNull Context uiContext) {
        switch (getAvailability()) {
            case AVAILABLE:
                return null;
            case NO_SYSTEM_FEATURE:
                return uiContext.getString(R.string.calibration_light_sensor_no_feature);
            case NO_HARDWARE_DRIVER:
                return uiContext.getString(R.string.calibration_light_sensor_no_hardware);
            default:
                return uiContext.getString(R.string.calibration_light_sensor_no_hardware);
        }
    }

    public boolean isListening() {
        return listening;
    }

    /**
     * 开始监听；采样延迟为 {@link SensorManager#SENSOR_DELAY_NORMAL}（约 200ms）。
     *
     * @param listener 可选；若仅使用 {@link #getAmbientLightLiveData()} 可传 null。
     */
    public void startListening(@Nullable OnLightSensorChangedListener listener) {
        if (sensorManager == null || !hasLightSensor()) {
            return;
        }
        Sensor light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (light == null) {
            return;
        }
        stopListeningInternal(false);
        this.listener = listener;
        lastThrottleEmitElapsedMs = 0L;
        pendingValid = false;
        sensorManager.registerListener(sensorEventListener, light, SensorManager.SENSOR_DELAY_NORMAL);
        listening = true;
    }

    /**
     * 取消注册传感器并清除回调，应在 {@code onPause} / {@code onDestroy} 或标定结束时调用。
     */
    public void stopListening() {
        stopListeningInternal(true);
    }

    private void dispatchThrottled(float rawLux, float calLux, double luminanceCdM2, long elapsedMs) {
        ambientLiveData.postValue(new AmbientLightReading(rawLux, calLux, luminanceCdM2, elapsedMs));
        final OnLightSensorChangedListener cb = listener;
        if (cb != null) {
            mainHandler.post(() -> cb.onLightChanged(calLux, luminanceCdM2));
        }
    }

    private void stopListeningInternal(boolean clearListener) {
        if (sensorManager != null) {
            if (listening && pendingValid) {
                float raw = pendingRawLux;
                float cal = sensorCalibrationStore.applyToLux(raw);
                double flushL = computeLuminanceCdM2(cal);
                dispatchThrottled(
                        raw,
                        cal,
                        flushL,
                        SystemClock.elapsedRealtime());
            }
            sensorManager.unregisterListener(sensorEventListener);
        }
        listening = false;
        if (clearListener) {
            listener = null;
        }
    }

    /**
     * 绑定到 {@link LifecycleOwner}：{@code onPause} / {@code onDestroy} 时自动 {@link #stopListening()}。
     * 重复绑定会先解除上一任 Owner 上的观察者。
     */
    public void bindToLifecycle(@NonNull LifecycleOwner owner) {
        unbindLifecycle();
        boundLifecycleOwner = owner;
        owner.getLifecycle().addObserver(lifecycleAutoStop);
    }

    /**
     * 解除 {@link #bindToLifecycle(LifecycleOwner)} 注册（不会强制停止监听，仅移除生命周期回调）。
     */
    public void unbindLifecycle() {
        if (boundLifecycleOwner != null) {
            boundLifecycleOwner.getLifecycle().removeObserver(lifecycleAutoStop);
            boundLifecycleOwner = null;
        }
    }

    @NonNull
    public Context getAppContext() {
        return appContext;
    }
}
