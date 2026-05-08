package com.example.camera;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.camera.BuildConfig;
import com.example.camera.debug.CalibrationVerificationActivity;
import com.example.camera.sensor.SensorCalibrationStore;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 传感器 lux 校准：线性 / 分段 / 关闭，参数存于 {@link SensorCalibrationStore#PREFS_NAME}。
 */
public class SensorCalibrationActivity extends AppCompatActivity {

    private SensorCalibrationStore store;
    private TextView tvStatusLabel;
    private TextView tvStatusHint;
    private RadioGroup rgMode;
    private RadioButton radioOff;
    private RadioButton radioLinear;
    private RadioButton radioPiecewise;
    private View panelLinear;
    private View panelPiecewise;
    private EditText etScale;
    private EditText etOffset;
    private LinearLayout segmentContainer;
    private Button btnAddSegment;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sensor_calibration);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.sensor_calibration_title);
        }

        store = new SensorCalibrationStore(this);
        tvStatusLabel = findViewById(R.id.tv_cal_status_label);
        tvStatusHint = findViewById(R.id.tv_cal_status_hint);
        rgMode = findViewById(R.id.rg_cal_mode);
        radioOff = findViewById(R.id.radio_cal_off);
        radioLinear = findViewById(R.id.radio_cal_linear);
        radioPiecewise = findViewById(R.id.radio_cal_piecewise);
        panelLinear = findViewById(R.id.panel_linear);
        panelPiecewise = findViewById(R.id.panel_piecewise);
        etScale = findViewById(R.id.et_cal_scale);
        etOffset = findViewById(R.id.et_cal_offset);
        segmentContainer = findViewById(R.id.segment_container);
        btnAddSegment = findViewById(R.id.btn_add_segment);
        Button btnApplyRecommended = findViewById(R.id.btn_cal_apply_recommended);
        Button btnRestore = findViewById(R.id.btn_cal_restore);
        Button btnSave = findViewById(R.id.btn_cal_save);
        Button btnVerify = findViewById(R.id.btn_calibration_verify);

        rgMode.setOnCheckedChangeListener((g, checkedId) -> updateModePanels());

        btnAddSegment.setOnClickListener(v -> addSegmentRow(null));
        btnApplyRecommended.setOnClickListener(v -> onApplyRecommended());
        btnRestore.setOnClickListener(v -> onRestoreDefault());
        btnSave.setOnClickListener(v -> onSave());
        if (BuildConfig.DEBUG && btnVerify != null) {
            btnVerify.setVisibility(View.VISIBLE);
            btnVerify.setOnClickListener(v ->
                    startActivity(new Intent(this, CalibrationVerificationActivity.class)));
        }

        loadFromStore();
        refreshStatusTexts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatusTexts();
    }

    private void refreshStatusTexts() {
        tvStatusLabel.setText(store.formatModeStatusLabel(this));
        tvStatusHint.setText(store.formatStatusHint(this));
    }

    private void loadFromStore() {
        String mode = store.getMode();
        if (SensorCalibrationStore.MODE_LINEAR.equals(mode)) {
            radioLinear.setChecked(true);
        } else if (SensorCalibrationStore.MODE_PIECEWISE.equals(mode)) {
            radioPiecewise.setChecked(true);
        } else {
            radioOff.setChecked(true);
        }
        etScale.setText(String.format(Locale.US, "%s", store.getScale()));
        etOffset.setText(String.format(Locale.US, "%s", store.getOffset()));
        segmentContainer.removeAllViews();
        List<SensorCalibrationStore.LuxSegment> segments = store.readSegments();
        if (segments.isEmpty()) {
            addSegmentRow(new SensorCalibrationStore.LuxSegment(0.0, 1000.0, 1.0));
        } else {
            for (SensorCalibrationStore.LuxSegment s : segments) {
                addSegmentRow(s);
            }
        }
        updateModePanels();
    }

    private void updateModePanels() {
        int id = rgMode.getCheckedRadioButtonId();
        boolean linear = id == R.id.radio_cal_linear;
        boolean piece = id == R.id.radio_cal_piecewise;
        panelLinear.setVisibility(linear ? View.VISIBLE : View.GONE);
        panelPiecewise.setVisibility(piece ? View.VISIBLE : View.GONE);
    }

    private void addSegmentRow(@Nullable SensorCalibrationStore.LuxSegment s) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_lux_segment_row, segmentContainer, false);
        EditText etMin = row.findViewById(R.id.et_seg_min);
        EditText etMax = row.findViewById(R.id.et_seg_max);
        EditText etSc = row.findViewById(R.id.et_seg_scale);
        Button btnRemove = row.findViewById(R.id.btn_seg_remove);
        if (s != null) {
            etMin.setText(formatSegBound(s.minLux));
            etMax.setText(formatSegBound(s.maxLux));
            etSc.setText(String.format(Locale.US, "%s", s.scale));
        } else {
            etMin.setText("0");
            etMax.setText("1000");
            etSc.setText("1");
        }
        btnRemove.setOnClickListener(v -> {
            segmentContainer.removeView(row);
            if (segmentContainer.getChildCount() == 0) {
                addSegmentRow(null);
            }
        });
        segmentContainer.addView(row);
    }

    @NonNull
    private static String formatSegBound(double v) {
        if (v >= Double.MAX_VALUE / 2) {
            return "1e308";
        }
        return String.format(Locale.US, "%s", v);
    }

    private void onApplyRecommended() {
        SensorCalibrationStore.PendingRecommendation p = store.readPendingRecommendation();
        if (p == null) {
            showDialogMessage(R.string.sensor_cal_toast_no_pending);
            return;
        }
        if (p.linear) {
            radioLinear.setChecked(true);
            etScale.setText(String.format(Locale.US, "%s", p.linearScale));
            etOffset.setText("0");
        } else {
            radioPiecewise.setChecked(true);
            segmentContainer.removeAllViews();
            List<SensorCalibrationStore.LuxSegment> segs;
            if (p.piecewiseLuxBands) {
                segs = SensorCalibrationStore.luxSegmentsByIlluminanceBands(
                        p.scaleLow, p.scaleMid, p.scaleHigh);
            } else {
                segs = SensorCalibrationStore.luxSegmentsFromLegacyAppLThresholds(
                        p.scaleLow, p.scaleMid, p.scaleHigh, p.thr1, p.thr2);
            }
            for (SensorCalibrationStore.LuxSegment s : segs) {
                addSegmentRow(s);
            }
        }
        updateModePanels();
        showDialogMessage(R.string.sensor_cal_toast_applied_pending);
    }

    private void onRestoreDefault() {
        store.clearToDefault();
        loadFromStore();
        refreshStatusTexts();
        showDialogMessage(R.string.sensor_cal_toast_cleared);
    }

    private void onSave() {
        int id = rgMode.getCheckedRadioButtonId();
        try {
            if (id == R.id.radio_cal_off) {
                store.saveOff();
            } else if (id == R.id.radio_cal_linear) {
                double scale = parseDoubleOrThrow(etScale.getText().toString());
                double offset = parseDoubleOrThrow(etOffset.getText().toString());
                store.saveLinear(scale, offset);
            } else if (id == R.id.radio_cal_piecewise) {
                List<SensorCalibrationStore.LuxSegment> segs = collectSegmentsFromUi();
                if (segs.isEmpty()) {
                    showDialogMessage(R.string.sensor_cal_error_piecewise_need_segment);
                    return;
                }
                for (SensorCalibrationStore.LuxSegment s : segs) {
                    if (s.minLux > s.maxLux) {
                        showDialogMessage(R.string.sensor_cal_error_piecewise_order);
                        return;
                    }
                }
                store.savePiecewise(segs);
            }
        } catch (NumberFormatException e) {
            showDialogMessage(R.string.sensor_cal_error_parse_number);
            return;
        } catch (JSONException e) {
            showDialogMessage(R.string.sensor_cal_error_parse_number);
            return;
        }
        refreshStatusTexts();
        showDialogMessage(R.string.sensor_cal_toast_saved);
    }

    private void showDialogMessage(int messageRes) {
        showDialogMessage(getString(messageRes));
    }

    private void showDialogMessage(@NonNull String message) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sensor_calibration_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static double parseDoubleOrThrow(@NonNull String s) throws NumberFormatException {
        String t = s.trim().replace(',', '.');
        if (t.isEmpty()) {
            throw new NumberFormatException();
        }
        return Double.parseDouble(t);
    }

    @NonNull
    private List<SensorCalibrationStore.LuxSegment> collectSegmentsFromUi() throws NumberFormatException {
        List<SensorCalibrationStore.LuxSegment> out = new ArrayList<>();
        int n = segmentContainer.getChildCount();
        for (int i = 0; i < n; i++) {
            View row = segmentContainer.getChildAt(i);
            EditText etMin = row.findViewById(R.id.et_seg_min);
            EditText etMax = row.findViewById(R.id.et_seg_max);
            EditText etSc = row.findViewById(R.id.et_seg_scale);
            double min = parseDoubleOrThrow(etMin.getText().toString());
            double max = parseMaxLux(etMax.getText().toString());
            double scale = parseDoubleOrThrow(etSc.getText().toString());
            out.add(new SensorCalibrationStore.LuxSegment(min, max, scale));
        }
        return out;
    }

    private static double parseMaxLux(@NonNull String s) throws NumberFormatException {
        String t = s.trim().replace(',', '.').toLowerCase(Locale.US);
        if (t.isEmpty()) {
            throw new NumberFormatException();
        }
        if ("inf".equals(t) || "+inf".equals(t) || "1e308".equals(t)) {
            return Double.MAX_VALUE;
        }
        return Double.parseDouble(t);
    }
}
