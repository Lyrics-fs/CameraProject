package com.example.camera.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.camera.model.calibration.CalibrationFactor;
import com.example.camera.model.calibration.LookupTable;
import com.example.camera.model.calibration.LookupTableRepository;

public class CalibrationRepository {

    private static final String TAG = "CalibrationRepo";
    private static final String PREFS_NAME = "calibration_prefs";
    private static final String KEY_A = "curve_a";
    private static final String KEY_B = "curve_b";
    private static final String KEY_R_SQUARED = "curve_r_squared";
    private static final String KEY_SOURCE = "curve_source";
    private static final String KEY_UPDATED_AT = "curve_updated_at";
    private static final String KEY_FIT_SAMPLE_COUNT = "curve_fit_sample_count";
    private static final String KEY_CLOUD_PROFILE_VERSION = "curve_cloud_profile_version";
    private static final String KEY_CLOUD_AGG_DEVICE_COUNT = "curve_cloud_agg_device_count";
    private static final String KEY_CLOUD_CONFIDENCE = "curve_cloud_confidence";
    private static final String KEY_APPLIED_CLOUD_VERSION = "curve_applied_cloud_version";
    private static final String KEY_LAST_CURVE_SYNC_MS = "curve_last_sync_ms";
    private static final String KEY_FIRST_LAUNCH_CLOUD_HINT = "curve_first_launch_cloud_hint_shown";
    private static final String KEY_ROLLBACK_HAS = "curve_rollback_has";
    private static final String KEY_ROLLBACK_A = "curve_rollback_a";
    private static final String KEY_ROLLBACK_B = "curve_rollback_b";
    private static final String KEY_ROLLBACK_SOURCE = "curve_rollback_source";
    private static final String KEY_ROLLBACK_UPDATED_AT = "curve_rollback_updated_at";
    private static final String KEY_ROLLBACK_R2 = "curve_rollback_r2";
    private static final String KEY_ROLLBACK_FIT_COUNT = "curve_rollback_fit_count";
    private static final String KEY_ROLLBACK_CLOUD_VER = "curve_rollback_cloud_ver";
    private static final String KEY_ROLLBACK_CLOUD_DEV = "curve_rollback_cloud_dev";
    private static final String KEY_ROLLBACK_CLOUD_CONF = "curve_rollback_cloud_conf";
    /** Debevec 响应 g(Z)，逗号分隔 256 个 double。 */
    private static final String KEY_DEBEVEC_G = "debevec_g_csv";
    private static final String KEY_ABS_CALIB_K = "abs_calib_k";
    private static final String KEY_ABS_GREY_DN = "abs_calib_grey_dn";
    private static final String KEY_ABS_GREY_L = "abs_calib_grey_L";
    private static final String KEY_ABS_CALIB_TS = "abs_calib_timestamp";
    /** {@link com.example.camera.model.calibration.CalibrationFactor#ABS_LEVEL_BRIGHTNESS_METER} 等 */
    private static final String KEY_ABS_CALIB_LEVEL = "abs_calib_level";
    /** 非空：Debevec 曲线来自启动时 HTTP 同步（如「云端用户曲线」）。本机重新标定 g/K 时会清除。 */
    private static final String KEY_DEBEVEC_CLOUD_SOURCE = "debevec_cloud_source";
    /** 本机曝光序列解算：R²_lnΔt（字符串 double），与当前本地 g 对应；云端拉取或无本地解算时应清除。 */
    private static final String KEY_DEBEVEC_R2_LOG_DELTA_T = "debevec_r2_log_delta_t";
    private static final String KEY_DEBEVEC_FRAME_COUNT = "debevec_frame_count";
    private static final String KEY_DEBEVEC_SEQUENCE_ISO = "debevec_sequence_iso";

    private final SharedPreferences sharedPreferences;
    private final Context appContext;
    private final LookupTableRepository lookupTableRepository;

    public CalibrationRepository(Context context) {
        Context app = context.getApplicationContext();
        this.appContext = app;
        sharedPreferences = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        lookupTableRepository = new LookupTableRepository(app);
    }

    /** 清除历史 APEX/BV 指数曲线在 SharedPreferences 中的键（应用已不再读取）。 */
    public void clearLegacyApexCurvePrefs() {
        sharedPreferences.edit()
                .remove(KEY_A)
                .remove(KEY_B)
                .remove(KEY_R_SQUARED)
                .remove(KEY_SOURCE)
                .remove(KEY_UPDATED_AT)
                .remove(KEY_FIT_SAMPLE_COUNT)
                .remove(KEY_CLOUD_PROFILE_VERSION)
                .remove(KEY_CLOUD_AGG_DEVICE_COUNT)
                .remove(KEY_CLOUD_CONFIDENCE)
                .putBoolean(KEY_ROLLBACK_HAS, false)
                .remove(KEY_ROLLBACK_A)
                .remove(KEY_ROLLBACK_B)
                .remove(KEY_ROLLBACK_SOURCE)
                .remove(KEY_ROLLBACK_UPDATED_AT)
                .remove(KEY_ROLLBACK_R2)
                .remove(KEY_ROLLBACK_FIT_COUNT)
                .remove(KEY_ROLLBACK_CLOUD_VER)
                .remove(KEY_ROLLBACK_CLOUD_DEV)
                .remove(KEY_ROLLBACK_CLOUD_CONF)
                .apply();
    }

    public int getAppliedCloudProfileVersion() {
        return sharedPreferences.getInt(KEY_APPLIED_CLOUD_VERSION, 0);
    }

    public void setAppliedCloudProfileVersion(int version) {
        sharedPreferences.edit().putInt(KEY_APPLIED_CLOUD_VERSION, Math.max(0, version)).apply();
    }

    public void markLastCurveSyncNow() {
        sharedPreferences.edit().putLong(KEY_LAST_CURVE_SYNC_MS, System.currentTimeMillis()).apply();
    }

    public boolean hasFirstLaunchCloudHintShown() {
        return sharedPreferences.getBoolean(KEY_FIRST_LAUNCH_CLOUD_HINT, false);
    }

    public void setFirstLaunchCloudHintShown() {
        sharedPreferences.edit().putBoolean(KEY_FIRST_LAUNCH_CLOUD_HINT, true).apply();
    }

    /**
     * 兼容设置项「恢复默认曲线」：仅清理旧版 APEX 曲线键，不影响 Debevec / 灰卡标定。
     */
    public void restoreDefaultCurve() {
        clearLegacyApexCurvePrefs();
    }

    /**
     * 云端 device_profiles 中的 A/B 曲线已不再应用；保留方法签名供同步逻辑调用，始终返回 false。
     */
    public boolean tryApplyCloudDeviceProfile(
            com.example.camera.calibration.model.DeviceProfile profile
    ) {
        if (profile != null) {
            setAppliedCloudProfileVersion(Math.max(getAppliedCloudProfileVersion(), profile.getVersion()));
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Debevec g(Z) + 绝对亮度标定（灰卡 + 亮度计或传感器 L3 估算）
    // -------------------------------------------------------------------------

    /**
     * 持久化 Debevec 离散响应曲线 {@code g[0..255]}（本机写入；清除云端曲线来源标记）。
     *
     * @return {@code commit()} 是否成功；部分机型上 {@code apply()} 异步可能导致紧接着的读取竞态，故用同步提交。
     */
    public boolean saveDebevecG(double[] g) {
        if (g == null || g.length < 256) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Double.toString(g[i]));
        }
        boolean ok = sharedPreferences.edit()
                .remove(KEY_DEBEVEC_CLOUD_SOURCE)
                .remove(KEY_DEBEVEC_R2_LOG_DELTA_T)
                .remove(KEY_DEBEVEC_FRAME_COUNT)
                .remove(KEY_DEBEVEC_SEQUENCE_ISO)
                .putString(KEY_DEBEVEC_G, sb.toString())
                .commit();
        if (!ok) {
            Log.e(TAG, "saveDebevecG: SharedPreferences.commit returned false");
        }
        return ok;
    }

    /**
     * 与本机 {@link #saveDebevecG(double[])} 配套的解算质量与序列参数（供 Level2 云端上报判断）。
     * 应在成功写入 g 之后调用。
     */
    public void saveDebevecSolveMetadata(double rSquaredLogDeltaT, int frameCount, int iso) {
        SharedPreferences.Editor ed = sharedPreferences.edit()
                .putString(KEY_DEBEVEC_R2_LOG_DELTA_T, Double.toString(rSquaredLogDeltaT))
                .putInt(KEY_DEBEVEC_FRAME_COUNT, Math.max(0, frameCount))
                .putInt(KEY_DEBEVEC_SEQUENCE_ISO, Math.max(1, iso));
        ed.apply();
    }

    /** 无或未写入时返回 NaN。 */
    public double getDebevecRSquaredLogDeltaTForUpload() {
        String s = sharedPreferences.getString(KEY_DEBEVEC_R2_LOG_DELTA_T, null);
        if (s == null || s.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    public int getDebevecFrameCountForUpload() {
        return sharedPreferences.getInt(KEY_DEBEVEC_FRAME_COUNT, 0);
    }

    public int getDebevecSequenceIsoForUpload() {
        return sharedPreferences.getInt(KEY_DEBEVEC_SEQUENCE_ISO, 100);
    }

    /** @return {@code g} 长度 256，若未保存则 {@code null} */
    public double[] loadDebevecG() {
        String s = sharedPreferences.getString(KEY_DEBEVEC_G, null);
        if (s == null || s.isEmpty()) {
            return null;
        }
        String[] parts = s.split(",", -1);
        if (parts.length < 256) {
            return null;
        }
        try {
            double[] g = new double[256];
            for (int i = 0; i < 256; i++) {
                g[i] = Double.parseDouble(parts[i].trim());
            }
            return g;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean hasDebevecG() {
        double[] g = loadDebevecG();
        return g != null && g.length >= 256;
    }

    /**
     * 持久化 K；{@link CalibrationFactor#ABS_LEVEL_SENSOR_LUX_ESTIMATE} 为本地 L3，
     * 不应作为高精度云端曲线提交（当前上传管线亦不依赖本字段，仅作区分展示）。
     */
    public void saveCalibrationFactor(CalibrationFactor factor) {
        if (factor == null || !factor.isValid()) {
            return;
        }
        SharedPreferences.Editor ed = sharedPreferences.edit()
                .putLong(KEY_ABS_CALIB_K, Double.doubleToLongBits(factor.k))
                .putLong(KEY_ABS_GREY_DN, Double.doubleToLongBits(factor.greyCardPixelValue))
                .putLong(KEY_ABS_GREY_L, Double.doubleToLongBits(factor.greyCardLuminance))
                .putLong(KEY_ABS_CALIB_TS, factor.calibrationTimestamp);
        // Level2（亮度计）：不写入 level 键，与引入 L3 前的 prefs 形态一致；仅 L3 写入标记。
        if (factor.isSensorLuxEstimate()) {
            ed.putInt(KEY_ABS_CALIB_LEVEL, CalibrationFactor.ABS_LEVEL_SENSOR_LUX_ESTIMATE);
        } else {
            ed.remove(KEY_ABS_CALIB_LEVEL);
        }
        ed.remove(KEY_DEBEVEC_CLOUD_SOURCE);
        ed.apply();
    }

    public CalibrationFactor loadCalibrationFactor() {
        if (!sharedPreferences.contains(KEY_ABS_CALIB_K)
                || !sharedPreferences.contains(KEY_ABS_GREY_L)) {
            return null;
        }
        double k = Double.longBitsToDouble(sharedPreferences.getLong(KEY_ABS_CALIB_K, 0L));
        double greyDn = Double.longBitsToDouble(sharedPreferences.getLong(KEY_ABS_GREY_DN, 0L));
        double greyL = Double.longBitsToDouble(sharedPreferences.getLong(KEY_ABS_GREY_L, 0L));
        long ts = sharedPreferences.getLong(KEY_ABS_CALIB_TS, 0L);
        int level = CalibrationFactor.ABS_LEVEL_BRIGHTNESS_METER;
        if (sharedPreferences.contains(KEY_ABS_CALIB_LEVEL)
                && sharedPreferences.getInt(KEY_ABS_CALIB_LEVEL, CalibrationFactor.ABS_LEVEL_BRIGHTNESS_METER)
                == CalibrationFactor.ABS_LEVEL_SENSOR_LUX_ESTIMATE) {
            level = CalibrationFactor.ABS_LEVEL_SENSOR_LUX_ESTIMATE;
        }
        CalibrationFactor f = new CalibrationFactor(k, greyDn, greyL, ts, level);
        return f.isValid() ? f : null;
    }

    /** 已同时存在有效的 {@link #loadDebevecG()} 与 {@link #loadCalibrationFactor()}。 */
    public boolean isAbsoluteLuminanceCalibrated() {
        return hasDebevecG() && loadCalibrationFactor() != null;
    }

    /** Level 1：是否已有可用的实验室 DN→L 查表（与 {@link LookupTableRepository} 同源持久化）。 */
    public boolean hasLookupTable() {
        return lookupTableRepository != null && lookupTableRepository.isAvailable();
    }

    /**
     * Level 1：查表条目数量摘要。
     * 无持久化仓库时返回空串；尚未保存查表时返回「无查表」。
     */
    public String getLookupTableInfo() {
        if (lookupTableRepository == null) {
            return "";
        }
        LookupTable table = lookupTableRepository.load();
        if (table == null) {
            return "无查表";
        }
        return table.getEntries().size() + " 组";
    }

    /** 清除绝对标定系数（保留或同时清除 g 由参数决定）。 */
    public void clearAbsoluteCalibration(boolean alsoClearDebevecG) {
        SharedPreferences.Editor ed = sharedPreferences.edit()
                .remove(KEY_ABS_CALIB_K)
                .remove(KEY_ABS_GREY_DN)
                .remove(KEY_ABS_GREY_L)
                .remove(KEY_ABS_CALIB_TS)
                .remove(KEY_ABS_CALIB_LEVEL);
        if (alsoClearDebevecG) {
            ed.remove(KEY_DEBEVEC_G)
                    .remove(KEY_DEBEVEC_R2_LOG_DELTA_T)
                    .remove(KEY_DEBEVEC_FRAME_COUNT)
                    .remove(KEY_DEBEVEC_SEQUENCE_ISO);
        }
        ed.remove(KEY_DEBEVEC_CLOUD_SOURCE);
        ed.apply();
    }

    /** 写入 Level1 查表（与 {@link LookupTableRepository#save} 同源）。 */
    public void persistLookupTable(LookupTable table) {
        if (lookupTableRepository == null || table == null) {
            return;
        }
        lookupTableRepository.save(table);
    }

    /**
     * 启动时云端同步：写入 Debevec {@code g}，可选绝对标定系数，并标记 {@link #KEY_DEBEVEC_CLOUD_SOURCE}。
     */
    public void applyCloudDebevecSnapshot(double[] g, CalibrationFactor factorOrNull, String cloudSourceLabel) {
        if (g == null || g.length < 256) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Double.toString(g[i]));
        }
        SharedPreferences.Editor ed = sharedPreferences.edit().putString(KEY_DEBEVEC_G, sb.toString());
        if (factorOrNull != null && factorOrNull.isValid()) {
            ed.putLong(KEY_ABS_CALIB_K, Double.doubleToLongBits(factorOrNull.k))
                    .putLong(KEY_ABS_GREY_DN, Double.doubleToLongBits(factorOrNull.greyCardPixelValue))
                    .putLong(KEY_ABS_GREY_L, Double.doubleToLongBits(factorOrNull.greyCardLuminance))
                    .putLong(KEY_ABS_CALIB_TS, factorOrNull.calibrationTimestamp);
            if (factorOrNull.isSensorLuxEstimate()) {
                ed.putInt(KEY_ABS_CALIB_LEVEL, CalibrationFactor.ABS_LEVEL_SENSOR_LUX_ESTIMATE);
            } else {
                ed.remove(KEY_ABS_CALIB_LEVEL);
            }
        } else {
            ed.remove(KEY_ABS_CALIB_K)
                    .remove(KEY_ABS_GREY_DN)
                    .remove(KEY_ABS_GREY_L)
                    .remove(KEY_ABS_CALIB_TS)
                    .remove(KEY_ABS_CALIB_LEVEL);
        }
        if (cloudSourceLabel != null && !cloudSourceLabel.isEmpty()) {
            ed.putString(KEY_DEBEVEC_CLOUD_SOURCE, cloudSourceLabel);
        }
        ed.remove(KEY_DEBEVEC_R2_LOG_DELTA_T)
                .remove(KEY_DEBEVEC_FRAME_COUNT)
                .remove(KEY_DEBEVEC_SEQUENCE_ISO);
        ed.apply();
    }

    /** 非空表示 Debevec 数据来自云端 HTTP 同步的展示标记。 */
    public String getDebevecCloudSourceLabel() {
        return sharedPreferences.getString(KEY_DEBEVEC_CLOUD_SOURCE, "");
    }
}
