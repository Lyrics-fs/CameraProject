package com.example.camera.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.camera.model.calibration.CurveParams;

public class CalibrationRepository {
    private static final String PREFS_NAME = "calibration_prefs";
    private static final String KEY_A = "curve_a";
    private static final String KEY_B = "curve_b";
    private static final String KEY_SOURCE = "curve_source";
    private static final String KEY_UPDATED_AT = "curve_updated_at";
    private static final String KEY_LAST_SESSION_SUMMARY = "last_session_summary";
    private final SharedPreferences sharedPreferences;

    public CalibrationRepository(Context context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public CurveParams loadCurveParams() {
        if (!sharedPreferences.contains(KEY_A) || !sharedPreferences.contains(KEY_B)) {
            return CurveParams.prior();
        }
        double a = Double.longBitsToDouble(sharedPreferences.getLong(KEY_A, Double.doubleToLongBits(CurveParams.PRIOR_A)));
        double b = Double.longBitsToDouble(sharedPreferences.getLong(KEY_B, Double.doubleToLongBits(CurveParams.PRIOR_B)));
        long updatedAt = sharedPreferences.getLong(KEY_UPDATED_AT, 0L);
        String source = sharedPreferences.getString(KEY_SOURCE, CurveParams.Source.PRIOR.name());
        CurveParams params = new CurveParams(a, b, parseSource(source), updatedAt);
        return params.isValid() ? params : CurveParams.prior();
    }

    public void saveCurveParams(CurveParams params) {
        if (params == null || !params.isValid()) return;
        sharedPreferences.edit()
                .putLong(KEY_A, Double.doubleToLongBits(params.a))
                .putLong(KEY_B, Double.doubleToLongBits(params.b))
                .putString(KEY_SOURCE, params.source.name())
                .putLong(KEY_UPDATED_AT, params.updatedAt)
                .apply();
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
}
