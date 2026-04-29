package com.example.camera.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.camera.calibration.model.CalibrationUploadData;
import com.example.camera.calibration.model.CalibrationUploadPolicy;
import com.example.camera.data.local.CalibrationRecordStore;
import com.example.camera.model.calibration.CurveParams;
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
    private static final String KEY_LAST_SESSION_SUMMARY = "last_session_summary";
    private final SharedPreferences sharedPreferences;
    private final CalibrationRecordStore calibrationRecordStore;
    private final Context appContext;

    public CalibrationRepository(Context context) {
        Context app = context.getApplicationContext();
        this.appContext = app;
        sharedPreferences = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        calibrationRecordStore = new CalibrationRecordStore(app);
    }

    public CurveParams loadCurveParams() {
        if (!sharedPreferences.contains(KEY_A) || !sharedPreferences.contains(KEY_B)) {
            return CurveParams.prior();
        }
        double a = Double.longBitsToDouble(sharedPreferences.getLong(KEY_A, Double.doubleToLongBits(CurveParams.PRIOR_A)));
        double b = Double.longBitsToDouble(sharedPreferences.getLong(KEY_B, Double.doubleToLongBits(CurveParams.PRIOR_B)));
        long updatedAt = sharedPreferences.getLong(KEY_UPDATED_AT, 0L);
        String source = sharedPreferences.getString(KEY_SOURCE, CurveParams.Source.PRIOR.name());
        double r2 = 0.0;
        if (sharedPreferences.contains(KEY_R_SQUARED)) {
            r2 = Double.longBitsToDouble(sharedPreferences.getLong(KEY_R_SQUARED, 0L));
        }
        CurveParams params = new CurveParams(a, b, parseSource(source), updatedAt, r2);
        params.setFitSampleCount(sharedPreferences.getInt(KEY_FIT_SAMPLE_COUNT, 0));
        if (params.source == CurveParams.Source.CLOUD) {
            params.setCloudProfileVersion(sharedPreferences.getInt(KEY_CLOUD_PROFILE_VERSION, 0));
            params.setCloudAggregatedDeviceCount(sharedPreferences.getInt(KEY_CLOUD_AGG_DEVICE_COUNT, 0));
            double conf = 0.0;
            if (sharedPreferences.contains(KEY_CLOUD_CONFIDENCE)) {
                conf = Double.longBitsToDouble(sharedPreferences.getLong(KEY_CLOUD_CONFIDENCE, 0L));
            }
            params.setCloudConfidence(conf);
        }
        return params.isValid() ? params : CurveParams.prior();
    }

    public void saveCurveParams(CurveParams params) {
        if (params == null || !params.isValid()) return;
        android.content.SharedPreferences.Editor ed = sharedPreferences.edit()
                .putLong(KEY_A, Double.doubleToLongBits(params.a))
                .putLong(KEY_B, Double.doubleToLongBits(params.b))
                .putLong(KEY_R_SQUARED, Double.doubleToLongBits(params.getRSquared()))
                .putString(KEY_SOURCE, params.source.name())
                .putLong(KEY_UPDATED_AT, params.updatedAt)
                .putInt(KEY_FIT_SAMPLE_COUNT, params.getFitSampleCount());
        if (params.source == CurveParams.Source.CLOUD) {
            ed.putInt(KEY_CLOUD_PROFILE_VERSION, params.getCloudProfileVersion());
            ed.putInt(KEY_CLOUD_AGG_DEVICE_COUNT, params.getCloudAggregatedDeviceCount());
            ed.putLong(KEY_CLOUD_CONFIDENCE, Double.doubleToLongBits(params.getCloudConfidence()));
        } else {
            ed.remove(KEY_CLOUD_PROFILE_VERSION);
            ed.remove(KEY_CLOUD_AGG_DEVICE_COUNT);
            ed.remove(KEY_CLOUD_CONFIDENCE);
        }
        ed.apply();
    }

    /** 当前已应用的云端 profile 版本（非云端来源时为上次记录值，供同步比较）。 */
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

    /** 覆盖写入回滚快照（在更换云端曲线或用户标定前调用）。 */
    public void saveRollbackSnapshot(CurveParams current) {
        if (current == null || !current.isValid()) {
            return;
        }
        sharedPreferences.edit()
                .putBoolean(KEY_ROLLBACK_HAS, true)
                .putLong(KEY_ROLLBACK_A, Double.doubleToLongBits(current.a))
                .putLong(KEY_ROLLBACK_B, Double.doubleToLongBits(current.b))
                .putString(KEY_ROLLBACK_SOURCE, current.source.name())
                .putLong(KEY_ROLLBACK_UPDATED_AT, current.updatedAt)
                .putLong(KEY_ROLLBACK_R2, Double.doubleToLongBits(current.getRSquared()))
                .putInt(KEY_ROLLBACK_FIT_COUNT, current.getFitSampleCount())
                .putInt(KEY_ROLLBACK_CLOUD_VER, current.getCloudProfileVersion())
                .putInt(KEY_ROLLBACK_CLOUD_DEV, current.getCloudAggregatedDeviceCount())
                .putLong(KEY_ROLLBACK_CLOUD_CONF, Double.doubleToLongBits(current.getCloudConfidence()))
                .apply();
    }

    public boolean hasRollbackSnapshot() {
        return sharedPreferences.getBoolean(KEY_ROLLBACK_HAS, false);
    }

    private void clearRollbackSnapshot() {
        sharedPreferences.edit()
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

    /** 恢复默认：优先恢复回滚快照，否则先验曲线。 */
    public void restoreDefaultCurve() {
        if (hasRollbackSnapshot()) {
            double a = Double.longBitsToDouble(sharedPreferences.getLong(KEY_ROLLBACK_A, Double.doubleToLongBits(CurveParams.PRIOR_A)));
            double b = Double.longBitsToDouble(sharedPreferences.getLong(KEY_ROLLBACK_B, Double.doubleToLongBits(CurveParams.PRIOR_B)));
            long updatedAt = sharedPreferences.getLong(KEY_ROLLBACK_UPDATED_AT, 0L);
            String src = sharedPreferences.getString(KEY_ROLLBACK_SOURCE, CurveParams.Source.PRIOR.name());
            double r2 = Double.longBitsToDouble(sharedPreferences.getLong(KEY_ROLLBACK_R2, 0L));
            CurveParams restored = new CurveParams(a, b, parseSource(src), updatedAt, r2);
            restored.setFitSampleCount(sharedPreferences.getInt(KEY_ROLLBACK_FIT_COUNT, 0));
            if (restored.source == CurveParams.Source.CLOUD) {
                restored.setCloudProfileVersion(sharedPreferences.getInt(KEY_ROLLBACK_CLOUD_VER, 0));
                restored.setCloudAggregatedDeviceCount(sharedPreferences.getInt(KEY_ROLLBACK_CLOUD_DEV, 0));
                restored.setCloudConfidence(Double.longBitsToDouble(sharedPreferences.getLong(KEY_ROLLBACK_CLOUD_CONF, 0L)));
            }
            clearRollbackSnapshot();
            if (restored.isValid()) {
                saveCurveParams(restored);
                if (restored.source == CurveParams.Source.CLOUD) {
                    setAppliedCloudProfileVersion(restored.getCloudProfileVersion());
                } else if (restored.source == CurveParams.Source.PRIOR) {
                    setAppliedCloudProfileVersion(0);
                }
                return;
            }
        }
        clearRollbackSnapshot();
        saveCurveParams(CurveParams.prior());
        setAppliedCloudProfileVersion(0);
    }

    /**
     * 尝试应用云端标准曲线：不会覆盖用户已标定曲线；仅当版本更新或当前为先验/旧云端时写入。
     *
     * @return 是否写入了新曲线
     */
    public boolean tryApplyCloudDeviceProfile(
            com.example.camera.calibration.model.DeviceProfile profile
    ) {
        if (profile == null) {
            return false;
        }
        if (!Double.isFinite(profile.getDefaultA()) || profile.getDefaultA() <= 0.0
                || !Double.isFinite(profile.getDefaultB())) {
            return false;
        }
        if (CalibrationUploadPolicy.INSTANCE.isAbnormalParams(profile.getDefaultA(), profile.getDefaultB())) {
            return false;
        }
        CurveParams current = loadCurveParams();
        if (current.source == CurveParams.Source.CALIBRATED) {
            setAppliedCloudProfileVersion(Math.max(getAppliedCloudProfileVersion(), profile.getVersion()));
            markLastCurveSyncNow();
            return false;
        }
        if (current.source == CurveParams.Source.CLOUD
                && profile.getVersion() <= getAppliedCloudProfileVersion()) {
            markLastCurveSyncNow();
            return false;
        }
        saveRollbackSnapshot(current);
        long updatedMs = profile.getUpdatedAt() > 0L ? profile.getUpdatedAt() : System.currentTimeMillis();
        CurveParams cloud = CurveParams.fromCloudProfile(
                profile.getDefaultA(),
                profile.getDefaultB(),
                updatedMs,
                profile.getVersion(),
                profile.getSampleCount(),
                profile.getConfidence()
        );
        saveCurveParams(cloud);
        setAppliedCloudProfileVersion(profile.getVersion());
        markLastCurveSyncNow();
        return true;
    }

    public void saveLastSessionSummary(String summary) {
        sharedPreferences.edit().putString(KEY_LAST_SESSION_SUMMARY, summary).apply();
    }

    public String loadLastSessionSummary() {
        return sharedPreferences.getString(KEY_LAST_SESSION_SUMMARY, "");
    }

    private CurveParams.Source parseSource(String source) {
        try {
            return CurveParams.Source.valueOf(source);
        } catch (Exception ignore) {
            return CurveParams.Source.PRIOR;
        }
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
}
