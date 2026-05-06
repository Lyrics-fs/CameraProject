package com.example.camera.debug;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.Observer;

import com.example.camera.BuildConfig;
import com.example.camera.R;
import com.example.camera.sensor.AmbientLightReading;
import com.example.camera.sensor.LightSensorManager;
import com.example.camera.sensor.SensorCalibrationStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Debug：采集新样本并对比校准前后 App 亮度与亮度计的百分比误差（仅 {@link BuildConfig#DEBUG} 应从 UI 进入）。
 */
public class CalibrationVerificationActivity extends AppCompatActivity {

    private static final int MIN_SAMPLES = 5;
    private static final int MAX_SAMPLES = 10;
    private static final int ASCII_BAR_WIDTH = 12;

    private enum SessionState {
        IDLE,
        COLLECTING,
        DONE
    }

    private LightSensorManager lightSensorManager;
    private SensorCalibrationStore calibrationStore;

    private TextView tvPrep;
    private TextView tvLux;
    private TextView tvCount;
    private TextView tvReport;
    private Button btnStart;
    private Button btnRecord;
    private Button btnFinish;
    private Button btnExport;
    private Button btnReset;

    private SessionState state = SessionState.IDLE;
    private final List<CalibrationVerificationReport.VerificationSample> samples = new ArrayList<>();
    private float lastRawLux = Float.NaN;
    @Nullable
    private CalibrationVerificationReport.FullReport lastReport;

    private final Observer<AmbientLightReading> ambientObserver = reading -> {
        if (reading != null && Float.isFinite(reading.rawLux)) {
            lastRawLux = reading.rawLux;
        }
        refreshLuxLine();
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!BuildConfig.DEBUG) {
            finish();
            return;
        }
        setContentView(R.layout.activity_calibration_verification);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.cal_verify_title);
        }

        calibrationStore = new SensorCalibrationStore(this);
        lightSensorManager = new LightSensorManager(this);
        lightSensorManager.getAmbientLightLiveData().observe(this, ambientObserver);

        tvPrep = findViewById(R.id.tv_verify_prep);
        tvLux = findViewById(R.id.tv_verify_lux);
        tvCount = findViewById(R.id.tv_verify_count);
        tvReport = findViewById(R.id.tv_verify_report);
        btnStart = findViewById(R.id.btn_verify_start);
        btnRecord = findViewById(R.id.btn_verify_record);
        btnFinish = findViewById(R.id.btn_verify_finish);
        btnExport = findViewById(R.id.btn_verify_export);
        btnReset = findViewById(R.id.btn_verify_reset);

        tvPrep.setText(R.string.cal_verify_prep);

        btnStart.setOnClickListener(v -> onStartVerification());
        btnRecord.setOnClickListener(v -> onRecordSample());
        btnFinish.setOnClickListener(v -> onFinishVerification());
        btnExport.setOnClickListener(v -> onExportCsv());
        btnReset.setOnClickListener(v -> onResetSession());

        if (!lightSensorManager.hasLightSensor()) {
            btnStart.setEnabled(false);
            Toast.makeText(this, R.string.cal_verify_no_sensor, Toast.LENGTH_LONG).show();
        }

        updateUi();
        refreshLuxLine();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (BuildConfig.DEBUG && state == SessionState.COLLECTING && lightSensorManager.hasLightSensor()) {
            lightSensorManager.startListening(null);
        }
    }

    @Override
    protected void onPause() {
        if (lightSensorManager != null) {
            lightSensorManager.stopListening();
        }
        super.onPause();
    }

    private void onStartVerification() {
        if (!lightSensorManager.hasLightSensor()) {
            Toast.makeText(this, R.string.cal_verify_no_sensor, Toast.LENGTH_SHORT).show();
            return;
        }
        samples.clear();
        lastReport = null;
        state = SessionState.COLLECTING;
        lightSensorManager.startListening(null);
        updateUi();
    }

    private void onRecordSample() {
        if (state != SessionState.COLLECTING || samples.size() >= MAX_SAMPLES) {
            return;
        }
        if (!Float.isFinite(lastRawLux)) {
            Toast.makeText(this, R.string.cal_verify_need_lux, Toast.LENGTH_SHORT).show();
            return;
        }
        final float snapLux = lastRawLux;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(R.string.cal_verify_meter_hint);

        new AlertDialog.Builder(this)
                .setTitle(R.string.cal_verify_record_sample)
                .setMessage(String.format(Locale.getDefault(), "lux_raw = %.2f", snapLux))
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String s = input.getText() != null ? input.getText().toString().trim() : "";
                    double meter;
                    try {
                        meter = Double.parseDouble(s.replace(',', '.'));
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, R.string.sensor_cal_error_parse_number, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!Double.isFinite(meter) || meter <= 0.0) {
                        Toast.makeText(this, R.string.cal_verify_meter_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    samples.add(new CalibrationVerificationReport.VerificationSample(snapLux, meter));
                    updateUi();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onFinishVerification() {
        if (state != SessionState.COLLECTING) {
            return;
        }
        if (samples.size() < MIN_SAMPLES) {
            Toast.makeText(this, getString(R.string.cal_verify_need_more_samples, MIN_SAMPLES), Toast.LENGTH_SHORT).show();
            return;
        }
        lightSensorManager.stopListening();
        lastReport = CalibrationVerificationReport.computeFull(samples, calibrationStore);
        String text = CalibrationVerificationReport.formatReportText(lastReport);
        text += "\n" + CalibrationVerificationReport.formatAsciiComparisonChart(lastReport, ASCII_BAR_WIDTH);
        String verdict = verdictLine(lastReport.after.mape);
        text += "\n" + verdict;
        tvReport.setText(text);
        state = SessionState.DONE;
        updateUi();
        showVerdictDialog(verdict);
    }

    @NonNull
    private String verdictLine(double mapeAfter) {
        if (Double.isFinite(mapeAfter) && mapeAfter < 15.0) {
            return getString(R.string.cal_verify_verdict_good);
        }
        if (Double.isFinite(mapeAfter) && mapeAfter > 30.0) {
            return getString(R.string.cal_verify_verdict_retrain);
        }
        return getString(R.string.cal_verify_verdict_mid);
    }

    private void showVerdictDialog(@NonNull String verdict) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.cal_verify_finish)
                .setMessage(verdict)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void onExportCsv() {
        if (lastReport == null) {
            return;
        }
        try {
            File out = writeReportCsv(lastReport);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", out);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.cal_verify_export_csv)));
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.cal_verify_export_fail, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    @NonNull
    private File writeReportCsv(@NonNull CalibrationVerificationReport.FullReport report) throws IOException {
        File dir = new File(getFilesDir(), "debug_calibration");
        if (!dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File f = new File(dir, "calibration_verify_" + ts + ".csv");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(CalibrationVerificationReport.buildCsv(report).getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    private void onResetSession() {
        samples.clear();
        lastReport = null;
        tvReport.setText("");
        state = SessionState.IDLE;
        lightSensorManager.stopListening();
        lastRawLux = Float.NaN;
        updateUi();
        refreshLuxLine();
    }

    private void updateUi() {
        switch (state) {
            case IDLE:
                btnStart.setEnabled(lightSensorManager.hasLightSensor());
                btnRecord.setEnabled(false);
                btnFinish.setEnabled(false);
                btnExport.setEnabled(false);
                break;
            case COLLECTING:
                btnStart.setEnabled(false);
                btnRecord.setEnabled(samples.size() < MAX_SAMPLES);
                btnFinish.setEnabled(samples.size() >= MIN_SAMPLES);
                btnExport.setEnabled(false);
                break;
            case DONE:
                btnStart.setEnabled(false);
                btnRecord.setEnabled(false);
                btnFinish.setEnabled(false);
                btnExport.setEnabled(true);
                break;
        }
        tvCount.setText(getString(R.string.cal_verify_count_line,
                samples.size(), MAX_SAMPLES, MIN_SAMPLES, MAX_SAMPLES));
    }

    private void refreshLuxLine() {
        if (Float.isFinite(lastRawLux)) {
            tvLux.setText(getString(R.string.cal_verify_lux_line,
                    String.format(Locale.US, "%.2f", lastRawLux)));
        } else {
            tvLux.setText(getString(R.string.cal_verify_lux_line, "—"));
        }
    }
}
