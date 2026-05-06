package com.example.camera.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.camera.calibration.model.CalibrationUploadData;
import com.example.camera.calibration.model.CalibrationUploadPolicy;
import com.example.camera.data.local.CalibrationRecordStore;
import com.example.camera.model.calibration.CalibrationFactor;
import com.example.camera.upload.UploadManager;

import java.util.List;

public class CalibrationRepository {
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

    private final SharedPreferences sharedPreferences;
    private final CalibrationRecordStore calibrationRecordStore;
    private final Context appContext;

    public CalibrationRepository(Context context) {
        Context app = context.getApplicationContext();
        this.appContext = app;
        sharedPreferences = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        calibrationRecordStore = new CalibrationRecordStore(app);
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
    // 本地标定上传记录（Room，无个人身份信息）
    // -------------------------------------------------------------------------

    /** 保存一条标定快照；LOW 质量会记为不参与上传的状态。 */
    public void saveCalibrationRecord(CalibrationUploadData record) {
        if (record == null) {
            return;
        }
        calibrationRecordStore.saveCalibrationRecord(record);
        if (CalibrationUploadPolicy.INSTANCE.shouldUpload(record.getQuality())) {
            UploadManager.scheduleAutoUpload(appContext);
        }
    }

    /** 待上传：质量为 HIGH/MEDIUM 且仍处于 pending 的记录。 */
    public List<CalibrationUploadData> getPendingUploads() {
        return calibrationRecordStore.getPendingUploads();
    }

    /** 待上传条数（HIGH/MEDIUM 且 PENDING）。 */
    public int getPendingUploadCount() {
        return calibrationRecordStore.countPendingUploads();
    }

    /** 标记指定记录已成功上传（写入上传完成时间，供 30 天保留策略使用）。 */
    public void markAsUploaded(String uploadId) {
        if (uploadId == null || uploadId.isEmpty()) {
            return;
        }
        calibrationRecordStore.markAsUploaded(uploadId);
    }

    /** 已成功上传的历史记录（按上传完成时间倒序）。 */
    public List<CalibrationUploadData> getUploadHistory() {
        return calibrationRecordStore.getUploadHistory();
    }

    /** 当前仍处于失败状态、可重试或未过放弃期的记录。 */
    public List<CalibrationUploadData> getFailedUploads() {
        return calibrationRecordStore.getFailedUploads();
    }

    /** 某次上传失败时调用，用于 7 天放弃策略计时。 */
    public void markUploadFailed(String uploadId) {
        if (uploadId == null || uploadId.isEmpty()) {
            return;
        }
        calibrationRecordStore.markUploadFailed(uploadId);
    }

    // -------------------------------------------------------------------------
    // Debevec g(Z) + 绝对亮度标定（灰卡 + 亮度计）
    // -------------------------------------------------------------------------

    /** 持久化 Debevec 离散响应曲线 {@code g[0..255]}。 */
    public void saveDebevecG(double[] g) {
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
        sharedPreferences.edit().putString(KEY_DEBEVEC_G, sb.toString()).apply();
    }

    /** @return {@code g} 长度 256，若未保存则 {@code null} */
    public double[] loadDebevecG() {
        String s = sharedPreferences.getString(KEY_DEBEVEC_G, null);
        if (s == null || s.isEmpty()) {
            return null;
        }
        String[] parts = s.split(",");
        if (parts.length < 256) {
            return null;
        }
        try {
            double[] g = new double[256];
            for (int i = 0; i < 256; i++) {
                g[i] = Double.parseDouble(parts[i]);
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

    public void saveCalibrationFactor(CalibrationFactor factor) {
        if (factor == null || !factor.isValid()) {
            return;
        }
        sharedPreferences.edit()
                .putLong(KEY_ABS_CALIB_K, Double.doubleToLongBits(factor.k))
                .putLong(KEY_ABS_GREY_DN, Double.doubleToLongBits(factor.greyCardPixelValue))
                .putLong(KEY_ABS_GREY_L, Double.doubleToLongBits(factor.greyCardLuminance))
                .putLong(KEY_ABS_CALIB_TS, factor.calibrationTimestamp)
                .apply();
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
        CalibrationFactor f = new CalibrationFactor(k, greyDn, greyL, ts);
        return f.isValid() ? f : null;
    }

    /** 已同时存在有效的 {@link #loadDebevecG()} 与 {@link #loadCalibrationFactor()}。 */
    public boolean isAbsoluteLuminanceCalibrated() {
        return hasDebevecG() && loadCalibrationFactor() != null;
    }

    /** 清除绝对标定系数（保留或同时清除 g 由参数决定）。 */
    public void clearAbsoluteCalibration(boolean alsoClearDebevecG) {
        SharedPreferences.Editor ed = sharedPreferences.edit()
                .remove(KEY_ABS_CALIB_K)
                .remove(KEY_ABS_GREY_DN)
                .remove(KEY_ABS_GREY_L)
                .remove(KEY_ABS_CALIB_TS);
        if (alsoClearDebevecG) {
            ed.remove(KEY_DEBEVEC_G);
        }
        ed.apply();
    }
}
