package com.example.camera.sensor;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.camera.debug.DebugLuxCalibrationAnalyzer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.example.camera.R;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 环境光传感器 lux 校准参数（线性 / 分段 / 关闭），持久化在
 * {@code SharedPreferences} 文件 {@value #PREFS_NAME}。
 */
public final class SensorCalibrationStore {

    /** 与需求一致：独立 prefs 文件名即「sensor_calibration」。 */
    public static final String PREFS_NAME = "sensor_calibration";

    /** 调试分段推荐：低照度段 lux 上界（lux &lt; 此值属低段）。 */
    public static final double LUX_BAND_LOW_MAX = 100.0;
    /** 调试分段推荐：中照度段 lux 上界（&lt; 此值且 ≥ 低段下界属中段，否则属高段）。 */
    public static final double LUX_BAND_MID_MAX = 1000.0;

    private static final String KEY_MODE = "mode";
    private static final String KEY_SCALE = "scale";
    private static final String KEY_OFFSET = "offset";
    private static final String KEY_SEGMENTS = "segments";
    private static final String KEY_UPDATED_AT = "updated_at";
    private static final String KEY_PENDING = "pending_recommendation";

    public static final String MODE_OFF = "off";
    public static final String MODE_LINEAR = "linear";
    public static final String MODE_PIECEWISE = "piecewise";

    private static final String LEGACY_PREFS = "sensor_lux_calibration_debug";
    private static final String LEGACY_KEY_MODE = "mode";
    private static final String LEGACY_MODE_NONE = "none";
    private static final String LEGACY_MODE_LINEAR = "linear";
    private static final String LEGACY_MODE_PIECEWISE = "piecewise";
    private static final String LEGACY_KEY_SCALE_LINEAR = "scale_linear";
    private static final String LEGACY_KEY_SCALE_L = "scale_l";
    private static final String LEGACY_KEY_SCALE_M = "scale_m";
    private static final String LEGACY_KEY_SCALE_H = "scale_h";
    private static final String LEGACY_KEY_THR1 = "thr1";
    private static final String LEGACY_KEY_THR2 = "thr2";

    private final Context appContext;
    private final SharedPreferences prefs;

    public SensorCalibrationStore(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ensureMigratedFromLegacy();
    }

    private void ensureMigratedFromLegacy() {
        if (prefs.contains(KEY_MODE)) {
            return;
        }
        SharedPreferences old = appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        String legacyMode = old.getString(LEGACY_KEY_MODE, LEGACY_MODE_NONE);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor ed = prefs.edit();
        if (LEGACY_MODE_LINEAR.equals(legacyMode)) {
            double scale = Double.longBitsToDouble(old.getLong(LEGACY_KEY_SCALE_LINEAR, Double.doubleToLongBits(1.0)));
            ed.putString(KEY_MODE, MODE_LINEAR)
                    .putLong(KEY_SCALE, Double.doubleToLongBits(sanitizeScale(scale)))
                    .putLong(KEY_OFFSET, Double.doubleToLongBits(0.0))
                    .putString(KEY_SEGMENTS, "[]")
                    .putLong(KEY_UPDATED_AT, now);
        } else if (LEGACY_MODE_PIECEWISE.equals(legacyMode)) {
            double sL = Double.longBitsToDouble(old.getLong(LEGACY_KEY_SCALE_L, Double.doubleToLongBits(1.0)));
            double sM = Double.longBitsToDouble(old.getLong(LEGACY_KEY_SCALE_M, Double.doubleToLongBits(1.0)));
            double sH = Double.longBitsToDouble(old.getLong(LEGACY_KEY_SCALE_H, Double.doubleToLongBits(1.0)));
            double t1 = Double.longBitsToDouble(old.getLong(LEGACY_KEY_THR1, 0L));
            double t2 = Double.longBitsToDouble(old.getLong(LEGACY_KEY_THR2, 0L));
            try {
                String json = segmentsJsonFromLegacyAppL(sL, sM, sH, t1, t2);
                ed.putString(KEY_MODE, MODE_PIECEWISE)
                        .putLong(KEY_SCALE, Double.doubleToLongBits(1.0))
                        .putLong(KEY_OFFSET, Double.doubleToLongBits(0.0))
                        .putString(KEY_SEGMENTS, json)
                        .putLong(KEY_UPDATED_AT, now);
            } catch (Exception ignored) {
                ed.putString(KEY_MODE, MODE_OFF)
                        .putLong(KEY_SCALE, Double.doubleToLongBits(1.0))
                        .putLong(KEY_OFFSET, Double.doubleToLongBits(0.0))
                        .putString(KEY_SEGMENTS, "[]")
                        .putLong(KEY_UPDATED_AT, 0L);
            }
        } else {
            ed.putString(KEY_MODE, MODE_OFF)
                    .putLong(KEY_SCALE, Double.doubleToLongBits(1.0))
                    .putLong(KEY_OFFSET, Double.doubleToLongBits(0.0))
                    .putString(KEY_SEGMENTS, "[]")
                    .putLong(KEY_UPDATED_AT, 0L);
        }
        ed.apply();
    }

    @NonNull
    private static String segmentsJsonFromLegacyAppL(
            double scaleLow, double scaleMid, double scaleHigh,
            double thr1AppL, double thr2AppL
    ) throws JSONException {
        return segmentsToJson(luxSegmentsFromLegacyAppLThresholds(
                scaleLow, scaleMid, scaleHigh, thr1AppL, thr2AppL));
    }

    /**
     * 将调试分析得到的「按 App L 阈值分段」的 scale，转为 lux 轴上的分段区间（与 {@link #applyToLux} 一致）。
     */
    @NonNull
    public static List<LuxSegment> luxSegmentsFromLegacyAppLThresholds(
            double scaleLow, double scaleMid, double scaleHigh,
            double thr1AppL, double thr2AppL
    ) {
        double k = Math.PI / 0.18;
        double l1 = thr1AppL * k;
        double l2 = thr2AppL * k;
        List<LuxSegment> list = new ArrayList<>(3);
        list.add(new LuxSegment(0.0, l1, sanitizeScale(scaleLow)));
        list.add(new LuxSegment(l1, l2, sanitizeScale(scaleMid)));
        list.add(new LuxSegment(l2, Double.MAX_VALUE, sanitizeScale(scaleHigh)));
        return list;
    }

    /**
     * 按传感器 lux 固定分界：低 &lt; {@value #LUX_BAND_LOW_MAX}、中 [{@value #LUX_BAND_LOW_MAX}, {@value #LUX_BAND_MID_MAX})、高 ≥ {@value #LUX_BAND_MID_MAX}。
     * 区间端点用 {@link Math#nextDown(double)} 避免与相邻段重复命中。
     */
    @NonNull
    public static List<LuxSegment> luxSegmentsByIlluminanceBands(
            double scaleLow, double scaleMid, double scaleHigh
    ) {
        double lowMax = Math.nextDown(LUX_BAND_LOW_MAX);
        double midMax = Math.nextDown(LUX_BAND_MID_MAX);
        List<LuxSegment> list = new ArrayList<>(3);
        list.add(new LuxSegment(0.0, lowMax, sanitizeScale(scaleLow)));
        list.add(new LuxSegment(LUX_BAND_LOW_MAX, midMax, sanitizeScale(scaleMid)));
        list.add(new LuxSegment(LUX_BAND_MID_MAX, Double.MAX_VALUE, sanitizeScale(scaleHigh)));
        return list;
    }

    @NonNull
    private static String segmentsToJson(@NonNull List<LuxSegment> segments) throws JSONException {
        JSONArray arr = new JSONArray();
        for (LuxSegment s : segments) {
            arr.put(segmentToJson(s.minLux, s.maxLux, s.scale));
        }
        return arr.toString();
    }

    @NonNull
    private static JSONObject segmentToJson(double min, double max, double scale) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("min", min);
        o.put("max", max);
        o.put("scale", scale);
        return o;
    }

    public boolean isCalibrationActive() {
        String mode = getMode();
        return MODE_LINEAR.equals(mode) || MODE_PIECEWISE.equals(mode);
    }

    @NonNull
    public String getMode() {
        return prefs.getString(KEY_MODE, MODE_OFF);
    }

    public double getScale() {
        return Double.longBitsToDouble(prefs.getLong(KEY_SCALE, Double.doubleToLongBits(1.0)));
    }

    public double getOffset() {
        return Double.longBitsToDouble(prefs.getLong(KEY_OFFSET, Double.doubleToLongBits(0.0)));
    }

    public long getUpdatedAtMillis() {
        return prefs.getLong(KEY_UPDATED_AT, 0L);
    }

    /**
     * 将原始 lux 转为校准后 lux（关闭模式下原样返回）。
     */
    public float applyToLux(float rawLux) {
        if (!Float.isFinite(rawLux)) {
            return rawLux;
        }
        double raw = rawLux;
        String mode = getMode();
        if (MODE_OFF.equals(mode)) {
            return rawLux;
        }
        if (MODE_LINEAR.equals(mode)) {
            double scale = getScale();
            double offset = getOffset();
            if (!Double.isFinite(scale)) {
                scale = 1.0;
            }
            if (!Double.isFinite(offset)) {
                offset = 0.0;
            }
            double out = raw * scale + offset;
            return (float) out;
        }
        if (MODE_PIECEWISE.equals(mode)) {
            List<LuxSegment> segments = readSegments();
            for (LuxSegment s : segments) {
                if (raw >= s.minLux && raw <= s.maxLux) {
                    double out = raw * s.scale;
                    return (float) (Double.isFinite(out) ? out : rawLux);
                }
            }
            return rawLux;
        }
        return rawLux;
    }

    public void saveOff() {
        prefs.edit()
                .putString(KEY_MODE, MODE_OFF)
                .putLong(KEY_SCALE, Double.doubleToLongBits(1.0))
                .putLong(KEY_OFFSET, Double.doubleToLongBits(0.0))
                .putString(KEY_SEGMENTS, "[]")
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    public void saveLinear(double scale, double offset) {
        prefs.edit()
                .putString(KEY_MODE, MODE_LINEAR)
                .putLong(KEY_SCALE, Double.doubleToLongBits(sanitizeScale(scale)))
                .putLong(KEY_OFFSET, Double.doubleToLongBits(Double.isFinite(offset) ? offset : 0.0))
                .putString(KEY_SEGMENTS, "[]")
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    public void savePiecewise(@NonNull List<LuxSegment> segments) throws JSONException {
        JSONArray arr = new JSONArray();
        for (LuxSegment s : segments) {
            arr.put(segmentToJson(s.minLux, s.maxLux, s.scale));
        }
        prefs.edit()
                .putString(KEY_MODE, MODE_PIECEWISE)
                .putLong(KEY_SCALE, Double.doubleToLongBits(1.0))
                .putLong(KEY_OFFSET, Double.doubleToLongBits(0.0))
                .putString(KEY_SEGMENTS, arr.toString())
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    /** 与调试「确认保存」一致：写入当前推荐并立即应用。 */
    public void applyFromDebugAnalyzerResult(@NonNull DebugLuxCalibrationAnalyzer.Result r) {
        if (r.linear) {
            saveLinear(r.linearScale, 0.0);
        } else {
            try {
                savePiecewise(luxSegmentsByIlluminanceBands(
                        r.scaleLow, r.scaleMid, r.scaleHigh));
            } catch (JSONException e) {
                saveLinear(r.linearScale, 0.0);
            }
        }
    }

    public void savePendingRecommendation(@NonNull DebugLuxCalibrationAnalyzer.Result r) {
        try {
            JSONObject o = new JSONObject();
            o.put("linear", r.linear);
            o.put("linearScale", r.linearScale);
            o.put("scaleLow", r.scaleLow);
            o.put("scaleMid", r.scaleMid);
            o.put("scaleHigh", r.scaleHigh);
            o.put("thr1", r.thr1);
            o.put("thr2", r.thr2);
            o.put("piecewiseLuxBands", !r.linear);
            prefs.edit().putString(KEY_PENDING, o.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public boolean hasPendingRecommendation() {
        String s = prefs.getString(KEY_PENDING, null);
        return s != null && !s.isEmpty();
    }

    @Nullable
    public PendingRecommendation readPendingRecommendation() {
        String s = prefs.getString(KEY_PENDING, null);
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(s);
            PendingRecommendation p = new PendingRecommendation();
            p.linear = o.optBoolean("linear", true);
            p.linearScale = o.optDouble("linearScale", 1.0);
            p.scaleLow = o.optDouble("scaleLow", 1.0);
            p.scaleMid = o.optDouble("scaleMid", 1.0);
            p.scaleHigh = o.optDouble("scaleHigh", 1.0);
            p.thr1 = o.optDouble("thr1", 0.0);
            p.thr2 = o.optDouble("thr2", 0.0);
            p.piecewiseLuxBands = o.optBoolean("piecewiseLuxBands", false);
            return p;
        } catch (JSONException e) {
            return null;
        }
    }

    /** 恢复默认：关闭校准并清空推荐缓存。 */
    public void clearToDefault() {
        prefs.edit()
                .putString(KEY_MODE, MODE_OFF)
                .putLong(KEY_SCALE, Double.doubleToLongBits(1.0))
                .putLong(KEY_OFFSET, Double.doubleToLongBits(0.0))
                .putString(KEY_SEGMENTS, "[]")
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .remove(KEY_PENDING)
                .apply();
    }

    @NonNull
    public List<LuxSegment> readSegments() {
        List<LuxSegment> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_SEGMENTS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                double min = o.getDouble("min");
                double max = o.getDouble("max");
                double scale = o.getDouble("scale");
                out.add(new LuxSegment(min, max, sanitizeScale(scale)));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    /**
     * 主界面 / 校准页提示文案。
     */
    @NonNull
    public String formatStatusHint(@NonNull Context uiContext) {
        if (!isCalibrationActive()) {
            return uiContext.getString(R.string.sensor_cal_hint_uncalibrated);
        }
        long at = getUpdatedAtMillis();
        String timeStr = at > 0
                ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                .format(at)
                : "";
        if (MODE_LINEAR.equals(getMode())) {
            double sc = getScale();
            double off = getOffset();
            return uiContext.getString(R.string.sensor_cal_hint_calibrated_linear,
                    sc, off, timeStr);
        }
        int n = readSegments().size();
        return uiContext.getString(R.string.sensor_cal_hint_calibrated_piecewise, n, timeStr);
    }

    @NonNull
    public String formatModeStatusLabel(@NonNull Context uiContext) {
        if (isCalibrationActive()) {
            return uiContext.getString(R.string.sensor_cal_status_calibrated);
        }
        if (getUpdatedAtMillis() == 0L) {
            return uiContext.getString(R.string.sensor_cal_status_not_calibrated);
        }
        return uiContext.getString(R.string.sensor_cal_status_using_default);
    }

    private static double sanitizeScale(double scale) {
        if (!Double.isFinite(scale) || scale == 0.0) {
            return 1.0;
        }
        return scale;
    }

    public static final class LuxSegment {
        public final double minLux;
        public final double maxLux;
        public final double scale;

        public LuxSegment(double minLux, double maxLux, double scale) {
            this.minLux = minLux;
            this.maxLux = maxLux;
            this.scale = scale;
        }
    }

    public static final class PendingRecommendation {
        public boolean linear;
        public double linearScale;
        public double scaleLow;
        public double scaleMid;
        public double scaleHigh;
        public double thr1;
        public double thr2;
        /** true：分段按 lux 100/1000 分界；false：旧版按 App L 阈值换算的 lux 分段。 */
        public boolean piecewiseLuxBands;
    }
}
