package com.example.camera.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Environment;
import android.util.Range;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import com.example.camera.contract.CameraContract;
import com.example.camera.data.CalibrationRepository;
import com.example.camera.model.calibration.CurveParams;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 数据层Model实现
 * 负责数据计算、图像处理和文件操作
 */
public class CameraModel implements CameraContract.Model {
    private static final String TAG = "CameraModel";
    private static final double DEFAULT_TARGET_BV = -2.47; // log2(0.18), 中灰参考
    private static final int LUMA_SAMPLE_STEP = 4;
    private static final int DEFAULT_ISO = 100;
    private static final long DEFAULT_EXPOSURE_NS = 10_000_000L; // 10ms
    private static final double MAX_BV_STEP = 1.0; // 每次分析最多调整 1 档
    private static final int PREVIEW_SAFE_MIN_ISO = 100;
    private static final int PREVIEW_SAFE_MAX_ISO = 800;
    private static final long PREVIEW_SAFE_MIN_EXPOSURE_NS = 2_000_000L;   // 2ms
    private static final long PREVIEW_SAFE_MAX_EXPOSURE_NS = 33_000_000L;  // ~1/30s

    // 伪彩色（Hue）映射：沿用原来的蓝→青→绿→黄→红分段。
    private static final int[] PSEUDO_R = new int[256];
    private static final int[] PSEUDO_G = new int[256];
    private static final int[] PSEUDO_B = new int[256];

    static {
        for (int g = 0; g <= 255; g++) {
            int rP;
            int gP;
            int bP;
            if (g < 64) {
                rP = 0;
                gP = 0;
                bP = g * 4;
            } else if (g < 128) {
                int t = (g - 64) * 4;
                rP = 0;
                gP = t;
                bP = 255 - t;
            } else if (g < 192) {
                int t = (g - 128) * 4;
                rP = t;
                gP = 255 - t;
                bP = 0;
            } else {
                int t = (g - 192) * 4;
                rP = 255;
                gP = t;
                bP = 0;
            }
            PSEUDO_R[g] = rP;
            PSEUDO_G[g] = gP;
            PSEUDO_B[g] = bP;
        }
    }
    
    private CameraSettings cameraSettings;
    private AppState appState;
    private final CalibrationRepository calibrationRepository;
    private CurveParams activeCurveParams;
    
    public CameraModel(Context context) {
        this.cameraSettings = new CameraSettings();
        this.appState = new AppState();
        this.calibrationRepository = context != null ? new CalibrationRepository(context) : null;
        this.activeCurveParams = calibrationRepository != null
                ? calibrationRepository.loadCurveParams()
                : CurveParams.prior();
    }

    /**
     * 自动曝光推荐结果。
     */
    public static class ExposureRecommendation {
        public final int recommendedIso;
        public final long recommendedExposureTime;
        public final double targetBV;
        public final double currentSceneBV;

        public ExposureRecommendation(int recommendedIso,
                                      long recommendedExposureTime,
                                      double targetBV,
                                      double currentSceneBV) {
            this.recommendedIso = recommendedIso;
            this.recommendedExposureTime = recommendedExposureTime;
            this.targetBV = targetBV;
            this.currentSceneBV = currentSceneBV;
        }
    }
    
    @Override
    public CameraSettings getCameraSettings() {
        return cameraSettings;
    }
    
    @Override
    public void updateCameraSettings(CameraSettings settings) {
        this.cameraSettings = settings;
    }
    
    @Override
    public AppState getAppState() {
        return appState;
    }
    
    @Override
    public void updateAppState(AppState state) {
        this.appState = state;
    }
    
    @Override
    public double calculateExposureValue(int iso, long exposureTime, float aperture) {
        double exposureSeconds = exposureTime / 1_000_000_000.0;
        return (iso * exposureSeconds) / (aperture * aperture);
    }
    
    @Override
    public String formatExposureTime(long exposureTimeNs) {
        double exposureMs = exposureTimeNs / 1_000_000.0;
        if (exposureMs < 1) {
            return String.format("%.2fμs", exposureMs * 1000);
        } else {
            return String.format("%.2fms", exposureMs);
        }
    }

    /**
     * 对预览帧进行自动曝光分析，返回推荐的 ISO/曝光时间组合。
     * 说明：
     * 1) 仅采样 Y 通道，降低实时计算开销；
     * 2) 采用先调整曝光时间、再调整 ISO 的策略；
     * 3) 目标 BV 默认使用中灰参考值 -2.47（log2(0.18)）。
     */
    public ExposureRecommendation analyzeFrameForAutoExposure(ImageProxy image) {
        if (image == null || image.getPlanes().length == 0) {
            int fallbackIso = getCurrentOrDefaultIso();
            long fallbackExposure = getCurrentOrDefaultExposureTime();
            return new ExposureRecommendation(
                    fallbackIso, fallbackExposure, DEFAULT_TARGET_BV, DEFAULT_TARGET_BV);
        }

        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        ByteBuffer yBuffer = yPlane.getBuffer().duplicate();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();

        long sumLuma = 0L;
        int sampleCount = 0;
        int rowStep = Math.max(1, LUMA_SAMPLE_STEP);
        int colStep = Math.max(1, LUMA_SAMPLE_STEP);

        for (int y = 0; y < height; y += rowStep) {
            int rowStart = y * rowStride;
            for (int x = 0; x < width; x += colStep) {
                int index = rowStart + x * pixelStride;
                if (index >= 0 && index < yBuffer.limit()) {
                    sumLuma += (yBuffer.get(index) & 0xFF);
                    sampleCount++;
                }
            }
        }

        double averageLuma = sampleCount > 0 ? (sumLuma * 1.0 / sampleCount) : 128.0;
        double currentSceneBV = calculateBVFromLuminance(averageLuma);
        double targetBV = DEFAULT_TARGET_BV;
        double rawBvDelta = targetBV - currentSceneBV;
        double bvDelta = clampDouble(rawBvDelta, -MAX_BV_STEP, MAX_BV_STEP);

        int baseIso = getCurrentOrDefaultIso();
        long baseExposure = getCurrentOrDefaultExposureTime();
        double evFactor = Math.pow(2.0, bvDelta);

        Range<Long> exposureRange = cameraSettings.getExposureRange();
        Range<Integer> isoRange = cameraSettings.getIsoRange();

        long minExposure = exposureRange != null ? exposureRange.getLower() : 100_000L;
        long maxExposure = exposureRange != null ? exposureRange.getUpper() : 100_000_000L;
        int minIso = isoRange != null ? isoRange.getLower() : 100;
        int maxIso = isoRange != null ? isoRange.getUpper() : 3200;

        // 预览期使用保守范围，避免“推荐值一键应用后变黑/卡顿”。
        minExposure = Math.max(minExposure, PREVIEW_SAFE_MIN_EXPOSURE_NS);
        maxExposure = Math.min(maxExposure, PREVIEW_SAFE_MAX_EXPOSURE_NS);
        minIso = Math.max(minIso, PREVIEW_SAFE_MIN_ISO);
        maxIso = Math.min(maxIso, PREVIEW_SAFE_MAX_ISO);

        long recommendedExposure = clampLong((long) (baseExposure * evFactor), minExposure, maxExposure);
        double usedExposureFactor = Math.max(1e-6, recommendedExposure * 1.0 / Math.max(1L, baseExposure));
        double remainFactor = evFactor / usedExposureFactor;
        int recommendedIso = clampInt((int) Math.round(baseIso * remainFactor), minIso, maxIso);

        return new ExposureRecommendation(recommendedIso, recommendedExposure, targetBV, currentSceneBV);
    }
    
    @Override
    public Bitmap createPseudoColorImage(Bitmap originalBitmap, String exifBrightness) {
        return createPseudoColorImage(originalBitmap, exifBrightness, 0, 1.0f);
    }

    public Bitmap createPseudoColorImage(Bitmap originalBitmap, String exifBrightness, int rotationDegrees) {
        return createPseudoColorImage(originalBitmap, exifBrightness, rotationDegrees, 1.0f);
    }

    public Bitmap createPseudoColorImage(Bitmap originalBitmap, String exifBrightness, int rotationDegrees, float brightnessGain) {
        if (originalBitmap == null) return null;

        int width = originalBitmap.getWidth();
        int height = originalBitmap.getHeight();

        // 双变量伪彩色：
        // - Hue 仍由亮度（gray）决定（沿用原有灰度分段映射）
        // - Saturation 由局部对比度决定（使用 3x3 邻域 max-min，更贴近原有高质量效果）
        final float CONTRAST_GAIN = 3.55f; // 再提高色彩量感，同时维持低对比度强抑制
        final float CONTRAST_FLOOR = 0.014f; // 稍微回落门限：避免“又不够彩”
        final float CONTRAST_EXP = 1.35f; // 降低指数抑制：让中低对比度更快上色，同时保留 CONTRAST_FLOOR 的噪声控制
        final float INV_255 = 1f / 255f;

        float gain = brightnessGain;
        if (!Float.isFinite(gain) || gain <= 0f) gain = 1.0f;

        int[] pixels = new int[width * height];
        originalBitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        float[] gray = new float[width * height]; // 0..255（已乘 brightnessGain 并 clamp）
        float globalMin = 255f;
        float globalMax = 0f;
        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];
            int r = Color.red(rgb);
            int g = Color.green(rgb);
            int b = Color.blue(rgb);
            float luma = 0.299f * r + 0.587f * g + 0.114f * b;
            float boosted = luma * gain;
            if (boosted < 0f) boosted = 0f;
            if (boosted > 255f) boosted = 255f;
            gray[i] = boosted;
            if (boosted < globalMin) globalMin = boosted;
            if (boosted > globalMax) globalMax = boosted;
        }

        // 只用于 Hue 的映射：把本帧亮度范围拉到 [0,255]，让红/蓝更容易拉开。
        float hueInvRange = (globalMax - globalMin) > 1e-3f ? (255f / (globalMax - globalMin)) : 1f;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;

                float minG = 255f;
                float maxG = 0f;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0) yy = 0;
                    if (yy >= height) yy = height - 1;
                    int rowBase = yy * width;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0) xx = 0;
                        if (xx >= width) xx = width - 1;
                        float gVal = gray[rowBase + xx];
                        if (gVal < minG) minG = gVal;
                        if (gVal > maxG) maxG = gVal;
                    }
                }

                float contrast = (maxG - minG) * INV_255; // 0..1
                float contrastAdj;
                if (contrast <= CONTRAST_FLOOR) {
                    contrastAdj = 0f;
                } else {
                    // 把 [CONTRAST_FLOOR, 1] 重新映射到 [0, 1]
                    contrastAdj = (contrast - CONTRAST_FLOOR) / (1f - CONTRAST_FLOOR);
                    if (contrastAdj < 0f) contrastAdj = 0f;
                    if (contrastAdj > 1f) contrastAdj = 1f;
                }

                // 幂次映射：噪声通常表现为接近阈值的低对比度，幂次可强力抑制它
                float s = (float) Math.pow(contrastAdj, CONTRAST_EXP) * CONTRAST_GAIN;
                if (s < 0f) s = 0f;
                if (s > 1f) s = 1f;
                // 先保证颜色分明：当前阶段暂时忽略噪声彩花控制，使用全饱和输出
                s = 1f;

                // Hue：使用“全局拉伸后”的亮度值做分段映射
                int g255 = Math.round((gray[idx] - globalMin) * hueInvRange);
                if (g255 < 0) g255 = 0;
                if (g255 > 255) g255 = 255;

                // Hue：沿用原有灰度分段伪彩色
                int rP = 0, gP = 0, bP = 0;
                if (g255 < 64) {
                    rP = 0;
                    gP = 0;
                    bP = g255 * 4;
                } else if (g255 < 128) {
                    int t = (g255 - 64) * 4;
                    rP = 0;
                    gP = t;
                    bP = 255 - t;
                } else if (g255 < 192) {
                    int t = (g255 - 128) * 4;
                    rP = t;
                    gP = 255 - t;
                    bP = 0;
                } else {
                    int t = (g255 - 192) * 4;
                    rP = 255;
                    gP = t;
                    bP = 0;
                }

                int outR = Math.round(g255 + (rP - g255) * s);
                int outG = Math.round(g255 + (gP - g255) * s);
                int outB = Math.round(g255 + (bP - g255) * s);
                if (outR < 0) outR = 0;
                if (outR > 255) outR = 255;
                if (outG < 0) outG = 0;
                if (outG > 255) outG = 255;
                if (outB < 0) outB = 0;
                if (outB > 255) outB = 255;

                pixels[idx] = Color.rgb(outR, outG, outB);
            }
        }

        Bitmap pseudoBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);

        // 根据拍照帧方向动态旋转，避免结果图与原图方向不一致。
        Bitmap rotatedPseudoBitmap;
        if (rotationDegrees % 360 == 0) {
            rotatedPseudoBitmap = pseudoBitmap;
        } else {
            Matrix rotateMatrix = new Matrix();
            rotateMatrix.postRotate(rotationDegrees);
            rotatedPseudoBitmap = Bitmap.createBitmap(pseudoBitmap, 0, 0, width, height, rotateMatrix, true);
            pseudoBitmap.recycle();
        }
        
        // 添加图例和信息
        return addLegendAndInfo(rotatedPseudoBitmap, originalBitmap, exifBrightness);
    }
    
    /**
     * 为伪彩色图像添加图例和信息
     */
    private Bitmap addLegendAndInfo(Bitmap rotatedPseudoBitmap, Bitmap originalBitmap, String exifBrightness) {
        int rotatedWidth = rotatedPseudoBitmap.getWidth();
        int rotatedHeight = rotatedPseudoBitmap.getHeight();
        
        // 计算平均亮度
        int[][] points = {
                {originalBitmap.getWidth() / 4, originalBitmap.getHeight() / 4},
                {originalBitmap.getWidth() * 3 / 4, originalBitmap.getHeight() / 4},
                {originalBitmap.getWidth() / 4, originalBitmap.getHeight() * 3 / 4},
                {originalBitmap.getWidth() * 3 / 4, originalBitmap.getHeight() * 3 / 4},
                {originalBitmap.getWidth() / 2, originalBitmap.getHeight() / 2}
        };
        int sumR = 0, sumG = 0, sumB = 0;
        for (int[] pt : points) {
            int pixel = originalBitmap.getPixel(pt[0], pt[1]);
            sumR += Color.red(pixel);
            sumG += Color.green(pixel);
            sumB += Color.blue(pixel);
        }
        int avgR = sumR / points.length;
        int avgG = sumG / points.length;
        int avgB = sumB / points.length;
        double yValue = 0.299 * avgR + 0.587 * avgG + 0.114 * avgB;

        // 亮度L中心值计算
        float bv = Float.NaN;
        if (exifBrightness != null && !exifBrightness.equals("N/A") && !exifBrightness.equals("读取失败")) {
            try {
                if (exifBrightness.contains("/")) {
                    String[] parts = exifBrightness.split("/");
                    bv = Float.parseFloat(parts[0]) / Float.parseFloat(parts[1]);
                } else {
                    bv = Float.parseFloat(exifBrightness);
                }
            } catch (Exception e) {
                bv = Float.NaN;
            }
        }
        
        double Lcenter;
        if (!Float.isNaN(bv)) {
            Lcenter = computeLFromBv(bv, activeCurveParams);
        } else {
            Lcenter = yValue / 255.0 * 400 + 50;
        }

        // 创建带图例的最终图像
        final int legendWidth = 190;
        Bitmap finalBitmap = Bitmap.createBitmap(rotatedWidth + legendWidth, rotatedHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(finalBitmap);
        canvas.drawBitmap(rotatedPseudoBitmap, 0, 0, null);

        // 绘制图例
        drawLegend(canvas, rotatedWidth, rotatedHeight, legendWidth, Lcenter);
        
        // 绘制统计信息
        drawStatistics(canvas, originalBitmap, avgR, avgG, avgB, yValue, exifBrightness, bv);

        return finalBitmap;
    }
    
    /**
     * 绘制亮度图例
     */
    private void drawLegend(Canvas canvas, int rotatedWidth, int rotatedHeight, int legendWidth, double Lcenter) {
        // 图例标题
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(Color.BLACK);
        paint.setTextSize(32);
        canvas.drawText("亮度L (Hue)", rotatedWidth + 18, 48, paint);
        canvas.drawText("饱和度~对比度", rotatedWidth + 18, 84, paint);

        final int legendLevels = 4;
        double delta = Lcenter * 0.25;
        double[] lThresholds = new double[legendLevels + 1];
        for (int i = 0; i <= legendLevels; i++) {
            lThresholds[i] = Lcenter - delta + (2 * delta) * i / legendLevels;
        }

        int[][] colorPairs = {
                {Color.rgb(0, 0, 0), Color.rgb(0, 0, 255)},
                {Color.rgb(0, 0, 255), Color.rgb(0, 255, 0)},
                {Color.rgb(0, 255, 0), Color.rgb(255, 255, 0)},
                {Color.rgb(255, 255, 0), Color.rgb(255, 0, 0)}
        };

        int itemHeight = rotatedHeight / legendLevels;
        itemHeight = Math.max(itemHeight, 50);
        paint.setTextSize(40);

        for (int i = 0; i < legendLevels; i++) {
            int y = i * itemHeight;
            int startColor = colorPairs[i][0];
            int endColor = colorPairs[i][1];
            LinearGradient gradient = new LinearGradient(
                    rotatedWidth + 15, y + 10,
                    rotatedWidth + legendWidth - 15, y + itemHeight - 10,
                    new int[]{startColor, endColor}, null, Shader.TileMode.CLAMP
            );
            paint.setShader(gradient);
            canvas.drawRect(rotatedWidth + 10, y + 10, rotatedWidth + legendWidth - 10, y + itemHeight - 10, paint);
            paint.setShader(null);

            String lLabel = String.format("L\n%.2f\n↓\n%.2f", lThresholds[i], lThresholds[i + 1]);
            paint.setColor(Color.BLACK);
            paint.setTextSize(40);

            float lineHeight = paint.getTextSize() + 8;
            String[] lines = lLabel.split("\n");
            float totalHeight = lines.length * lineHeight;
            float textY = y + (itemHeight - totalHeight) / 2 + lineHeight;
            float textX = rotatedWidth + legendWidth / 2f - paint.measureText("00.00") / 2;

            for (int j = 0; j < lines.length; j++) {
                canvas.drawText(lines[j], textX, textY + j * lineHeight, paint);
            }
        }
    }
    
    /**
     * 原图中心区域（约 1/4×1/4）平均亮度 DN，与标定/预览中心 ROI 语义一致；大图子采样以控制耗时。
     */
    private double computeCenterRegionMeanDn(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return Double.NaN;
        }
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w < 1 || h < 1) {
            return Double.NaN;
        }
        int rw = Math.max(1, w / 4);
        int rh = Math.max(1, h / 4);
        int x0 = (w - rw) / 2;
        int y0 = (h - rh) / 2;
        int stepX = Math.max(1, rw / 64);
        int stepY = Math.max(1, rh / 64);
        double sum = 0.0;
        int n = 0;
        for (int y = y0; y < y0 + rh; y += stepY) {
            for (int x = x0; x < x0 + rw; x += stepX) {
                int p = bitmap.getPixel(x, y);
                sum += 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p);
                n++;
            }
        }
        return n > 0 ? sum / n : Double.NaN;
    }

    /**
     * 绘制统计信息（与拍照伪色图导出模板一致）
     */
    private void drawStatistics(Canvas canvas, Bitmap originalBitmap, int avgR, int avgG, int avgB,
                               double yValue, String exifBrightness, float bv) {
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(36);
        textPaint.setAntiAlias(true);
        textPaint.setShadowLayer(2.0f, 2, 2, Color.BLACK);

        CurveParams curve = activeCurveParams != null && activeCurveParams.isValid()
                ? activeCurveParams
                : CurveParams.prior();

        double centerDn = computeCenterRegionMeanDn(originalBitmap);
        String dnStr = Double.isFinite(centerDn)
                ? String.format(Locale.US, "%.1f", centerDn)
                : "—";

        String exifLine = (exifBrightness != null && !exifBrightness.isEmpty()) ? exifBrightness : "—";

        String curveFormulaLine = String.format(Locale.US,
                "标定曲线: L = %.4f × exp(%.4f × BV) cd/m²",
                curve.a, curve.b);
        String curveBvLine = "BV = log₂(DN/255)，DN 为 0–255 灰度";

        String qualityMetric;
        if (curve.source == CurveParams.Source.CALIBRATED) {
            qualityMetric = String.format(Locale.US, "%.3f", curve.getRSquared());
        } else if (curve.source == CurveParams.Source.CLOUD) {
            qualityMetric = String.format(Locale.US, "%.2f", curve.getCloudConfidence());
        } else {
            qualityMetric = "—";
        }
        String qualityLine = curve.source == CurveParams.Source.CLOUD
                ? String.format(Locale.CHINA, "置信度 = %s", qualityMetric)
                : String.format(Locale.CHINA, "拟合优度 R² = %s", qualityMetric);

        String sourceLine = formatCalibrationSourceLine(curve);

        double centerL = Double.isFinite(centerDn) ? computeLFromDn(centerDn) : Double.NaN;
        String centerLLine = Double.isFinite(centerL)
                ? String.format(Locale.US, "中心亮度: %.1f cd/m²", centerL)
                : "中心亮度: — cd/m²";

        final float x = 30f;
        float y = 52f;
        final float lineStep = 48f;

        canvas.drawText(String.format(Locale.getDefault(), "Avg RGB: R=%d, G=%d, B=%d", avgR, avgG, avgB), x, y, textPaint);
        y += lineStep;
        canvas.drawText(String.format(Locale.US, "Gray = %.2f | DN = %s", yValue, dnStr), x, y, textPaint);
        y += lineStep;
        canvas.drawText(String.format(Locale.CHINA, "EXIF BV = %s", exifLine), x, y, textPaint);
        y += lineStep;
        canvas.drawText(curveFormulaLine, x, y, textPaint);
        y += lineStep;
        canvas.drawText(curveBvLine, x, y, textPaint);
        y += lineStep;
        canvas.drawText(qualityLine, x, y, textPaint);
        y += lineStep;
        canvas.drawText(sourceLine, x, y, textPaint);
        y += lineStep;
        canvas.drawText(centerLLine, x, y, textPaint);
    }

    private static String formatCalibrationSourceLine(CurveParams curve) {
        if (curve.source == CurveParams.Source.CALIBRATED) {
            if (curve.updatedAt > 0L) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
                return String.format(Locale.CHINA, "来源: 本地标定 (%s)", sdf.format(new Date(curve.updatedAt)));
            }
            return "来源: 本地标定";
        }
        if (curve.source == CurveParams.Source.CLOUD) {
            return String.format(Locale.CHINA, "来源: 云端标准曲线 (v%d, 置信%.2f, %d台)",
                    curve.getCloudProfileVersion(),
                    curve.getCloudConfidence(),
                    curve.getCloudAggregatedDeviceCount());
        }
        return "来源: 先验曲线";
    }
    
    @Override
    public boolean saveImage(byte[] imageData, String fileName) {
        try {
            File file = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "Camera/" + fileName
            );
            
            // 确保目录存在
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(imageData);
                Log.d(TAG, "Image saved: " + file.getAbsolutePath());
                return true;
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to save image", e);
            return false;
        }
    }
    
    @Override
    public boolean saveImage(Bitmap bitmap, String fileName) {
        try {
            File file = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "Camera/" + fileName
            );
            
            // 确保目录存在
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            
            try (FileOutputStream output = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output);
                Log.d(TAG, "Bitmap saved: " + file.getAbsolutePath());
                return true;
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to save bitmap", e);
            return false;
        }
    }

    private double calculateBVFromLuminance(double averageLuma) {
        // 使用归一化亮度近似 BV，避免 log(0)。
        double normalized = Math.max(averageLuma / 255.0, 1e-4);
        return Math.log(normalized) / Math.log(2);
    }

    private int getCurrentOrDefaultIso() {
        int iso = cameraSettings.getIso();
        return iso > 0 ? iso : DEFAULT_ISO;
    }

    private long getCurrentOrDefaultExposureTime() {
        long exposure = cameraSettings.getExposureTime();
        return exposure > 0 ? exposure : DEFAULT_EXPOSURE_NS;
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public double computeLFromBv(float bv, CurveParams params) {
        CurveParams effective = params != null && params.isValid() ? params : CurveParams.prior();
        return effective.a * Math.exp(effective.b * bv);
    }

    public CurveParams getActiveCurveParams() {
        return activeCurveParams;
    }

    /** 由中心 ROI 平均 DN 经当前活动曲线（标定或先验）得到亮度 L（cd/m²）。 */
    public double computeLFromDn(double dn) {
        CurveParams params = activeCurveParams != null && activeCurveParams.isValid()
                ? activeCurveParams
                : CurveParams.prior();
        return params.computeL(dn);
    }

    public void setActiveCurveParams(CurveParams params) {
        CurveParams effective = params != null && params.isValid() ? params : CurveParams.prior();
        if (calibrationRepository != null && activeCurveParams != null && activeCurveParams.isValid()) {
            boolean changed =
                    effective.a != activeCurveParams.a
                            || effective.b != activeCurveParams.b
                            || effective.source != activeCurveParams.source;
            if (changed) {
                calibrationRepository.saveRollbackSnapshot(activeCurveParams);
            }
        }
        activeCurveParams = effective;
        if (calibrationRepository != null) {
            calibrationRepository.saveCurveParams(effective);
        }
    }

    /** 从 SharedPreferences 重新加载曲线（云端异步拉取完成后调用）。 */
    public void reloadActiveCurveFromRepository() {
        if (calibrationRepository != null) {
            activeCurveParams = calibrationRepository.loadCurveParams();
        }
    }

    public String getCurveSourceLabel() {
        CurveParams c = activeCurveParams != null ? activeCurveParams : CurveParams.prior();
        if (c.source == CurveParams.Source.CALIBRATED) {
            return "已标定";
        }
        if (c.source == CurveParams.Source.CLOUD) {
            return String.format(Locale.CHINA, "云端曲线·基于%d台设备", c.getCloudAggregatedDeviceCount());
        }
        return "先验公式";
    }

    /** 预览角标：来源 + 置信度 / R²。 */
    public String getCurveBadgeShortLabel() {
        CurveParams c = activeCurveParams != null ? activeCurveParams : CurveParams.prior();
        if (c.source == CurveParams.Source.CALIBRATED) {
            return String.format(Locale.CHINA, "已标定·R²%.2f", c.getRSquared());
        }
        if (c.source == CurveParams.Source.CLOUD) {
            return String.format(Locale.CHINA, "云端·%d台·置信%.2f",
                    c.getCloudAggregatedDeviceCount(),
                    c.getCloudConfidence());
        }
        return "先验公式";
    }

    public void saveCalibrationSummary(String summary) {
        if (calibrationRepository != null) {
            calibrationRepository.saveLastSessionSummary(summary);
        }
    }

    public String getCalibrationSummary() {
        return calibrationRepository != null ? calibrationRepository.loadLastSessionSummary() : "";
    }

    public CalibrationRepository getCalibrationRepository() {
        return calibrationRepository;
    }
}