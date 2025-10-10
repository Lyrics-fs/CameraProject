package com.example.camera.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Environment;
import android.util.Log;

import com.example.camera.contract.CameraContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 数据层Model实现
 * 负责数据计算、图像处理和文件操作
 */
public class CameraModel implements CameraContract.Model {
    private static final String TAG = "CameraModel";
    
    private CameraSettings cameraSettings;
    private AppState appState;
    private Context context;
    
    public CameraModel(Context context) {
        this.context = context;
        this.cameraSettings = new CameraSettings();
        this.appState = new AppState();
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
    
    @Override
    public Bitmap createPseudoColorImage(Bitmap originalBitmap, String exifBrightness) {
        if (originalBitmap == null) return null;
        
        int width = originalBitmap.getWidth();
        int height = originalBitmap.getHeight();

        // 生成伪彩色图像
        int[] pixels = new int[width * height];
        originalBitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int i = 0; i < pixels.length; i++) {
            int gray = Color.red(pixels[i]);
            int color;
            if (gray < 64) {
                color = Color.rgb(0, 0, gray * 4);
            } else if (gray < 128) {
                int blue = 255 - (gray - 64) * 4;
                int green = (gray - 64) * 4;
                color = Color.rgb(0, green, blue);
            } else if (gray < 192) {
                int green = 255 - (gray - 128) * 4;
                int red = (gray - 128) * 4;
                color = Color.rgb(red, green, 0);
            } else {
                int green = (gray - 192) * 4;
                color = Color.rgb(255, green, 0);
            }
            pixels[i] = color;
        }

        Bitmap pseudoBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);

        // 旋转图像
        Matrix rotateMatrix = new Matrix();
        rotateMatrix.postRotate(90);
        Bitmap rotatedPseudoBitmap = Bitmap.createBitmap(pseudoBitmap, 0, 0, width, height, rotateMatrix, true);
        pseudoBitmap.recycle();
        
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
            Lcenter = 2.9 * Math.exp(0.729 * bv);
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
        drawStatistics(canvas, avgR, avgG, avgB, yValue, exifBrightness, bv);

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
        canvas.drawText("亮度L (cd/m²)", rotatedWidth + 18, 48, paint);

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
     * 绘制统计信息
     */
    private void drawStatistics(Canvas canvas, int avgR, int avgG, int avgB, double yValue, String exifBrightness, float bv) {
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(36);
        textPaint.setAntiAlias(true);
        textPaint.setShadowLayer(2.0f, 2, 2, Color.BLACK);

        canvas.drawText(String.format("Avg RGB: R=%d, G=%d, B=%d", avgR, avgG, avgB), 30, 60, textPaint);
        canvas.drawText(String.format("Gray = %.2f", yValue), 30, 110, textPaint);
        canvas.drawText(String.format("EXIF BV = %s", exifBrightness), 30, 170, textPaint);

        String lResult;
        if (!Float.isNaN(bv)) {
            double L = 2.9 * Math.exp(0.729 * bv);
            lResult = String.format("L = 2.9 × exp(0.729×BV) = %.2f", L);
        } else {
            lResult = "L = N/A";
        }
        canvas.drawText(lResult, 30, 220, textPaint);
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
}