package com.example.camera.debug;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Debug 标定原始数据 CSV：私有目录 {@code files/debug_calibration/}。
 */
public final class DebugCalibrationCsvRecorder {

    private static final String DIR = "debug_calibration";
    private static final String HEADER = "时间戳,传感器lux,亮度计读数(cd/m²),App计算亮度(cd/m²),BV值";

    private final Context appContext;
    @Nullable
    private File currentFile;

    public DebugCalibrationCsvRecorder(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    @NonNull
    private File dir() {
        File d = new File(appContext.getFilesDir(), DIR);
        if (!d.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    private void ensureFile() throws IOException {
        if (currentFile != null && currentFile.isFile()) {
            return;
        }
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        currentFile = new File(dir(), "calibration_data_" + ts + ".csv");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(currentFile, true), StandardCharsets.UTF_8)) {
            w.write(HEADER);
            w.write('\n');
        }
    }

    public synchronized void appendRow(
            @NonNull String timestamp,
            double lux,
            double meterCdM2,
            double appComputedCdM2,
            double bv
    ) throws IOException {
        ensureFile();
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(currentFile, true), StandardCharsets.UTF_8)) {
            w.write(String.format(Locale.US, "%s,%.4f,%.4f,%.4f,%.4f\n",
                    timestamp, lux, meterCdM2, appComputedCdM2, bv));
        }
    }

    @Nullable
    public synchronized File getCurrentFile() {
        return currentFile != null && currentFile.isFile() ? currentFile : null;
    }

    public synchronized void clear() {
        if (currentFile != null && currentFile.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            currentFile.delete();
        }
        currentFile = null;
    }

    @NonNull
    public synchronized List<DebugLuxCalibrationAnalyzer.Row> readRowsForAnalysis() throws IOException {
        List<DebugLuxCalibrationAnalyzer.Row> out = new ArrayList<>();
        File f = getCurrentFile();
        if (f == null) {
            return out;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (first && line.startsWith("时间戳")) {
                    first = false;
                    continue;
                }
                first = false;
                String[] p = line.split(",");
                if (p.length < 5) {
                    continue;
                }
                try {
                    double lux = Double.parseDouble(p[1].trim());
                    double meter = Double.parseDouble(p[2].trim());
                    double appL = Double.parseDouble(p[3].trim());
                    double bv = Double.parseDouble(p[4].trim());
                    out.add(new DebugLuxCalibrationAnalyzer.Row(lux, meter, appL, bv));
                } catch (NumberFormatException ignored) {
                    // skip bad line
                }
            }
        }
        return out;
    }
}
