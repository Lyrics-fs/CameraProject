package com.example.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Range;

import androidx.annotation.Nullable;

import com.example.camera.contract.CameraContract;
import com.example.camera.model.CameraModel;
import com.example.camera.presenter.CameraPresenter;

import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ExampleUnitTest {

    private static Context testContext() {
        Context ctx = mock(Context.class);
        when(ctx.getApplicationContext()).thenReturn(ctx);
        when(ctx.getSystemService(Context.SENSOR_SERVICE)).thenReturn(null);
        SharedPreferences prefs = mock(SharedPreferences.class);
        when(prefs.contains(anyString())).thenReturn(false);
        when(ctx.getSharedPreferences(anyString(), eq(Context.MODE_PRIVATE))).thenReturn(prefs);
        return ctx;
    }

    @Test
    public void isoChange_updatesIsoAndResetsBrightnessSeekBar() {
        FakeView view = new FakeView();
        CameraPresenter presenter = new CameraPresenter(view, testContext());
        Range<Integer> isoRange = mockRange();
        Range<Long> exposureRange = mockRange();
        when(isoRange.getLower()).thenReturn(100);
        when(isoRange.getUpper()).thenReturn(1100);
        when(exposureRange.getLower()).thenReturn(1_000_000L);
        when(exposureRange.getUpper()).thenReturn(10_000_000L);
        presenter.setCameraRanges(isoRange, exposureRange, 1.8f);

        presenter.onIsoChanged(2500);

        assertEquals(450, view.lastIso);
        assertEquals(R.id.seekBarBrightness, view.lastSeekBarId);
        assertEquals(0, view.lastSeekBarProgress);
        assertEquals(450, view.appliedIso);
        assertTrue(view.lastExposureValue.startsWith("E = "));
    }

    @Test
    public void exposureChange_updatesExposureAndFormatsDisplay() {
        FakeView view = new FakeView();
        CameraPresenter presenter = new CameraPresenter(view, testContext());
        Range<Integer> isoRange = mockRange();
        Range<Long> exposureRange = mockRange();
        when(isoRange.getLower()).thenReturn(100);
        when(isoRange.getUpper()).thenReturn(1100);
        when(exposureRange.getLower()).thenReturn(1_000_000L);
        when(exposureRange.getUpper()).thenReturn(100_000_000L);
        presenter.setCameraRanges(isoRange, exposureRange, 1.8f);

        presenter.onExposureChanged(4000);

        assertEquals(R.id.seekBarBrightness, view.lastSeekBarId);
        assertEquals(0, view.lastSeekBarProgress);
        assertTrue(view.lastExposureText.contains("s"));
        assertTrue(view.appliedExposure > 0);
    }

    @Test
    public void brightnessChange_updatesGainAndClearsManualLabels() {
        FakeView view = new FakeView();
        CameraPresenter presenter = new CameraPresenter(view, testContext());
        Range<Integer> isoRange = mockRange();
        Range<Long> exposureRange = mockRange();
        when(isoRange.getLower()).thenReturn(100);
        when(isoRange.getUpper()).thenReturn(1100);
        when(exposureRange.getLower()).thenReturn(1_000_000L);
        when(exposureRange.getUpper()).thenReturn(10_000_000L);
        presenter.setCameraRanges(isoRange, exposureRange, 1.8f);

        presenter.onBrightnessChanged(5000);

        assertEquals("—", view.lastExposureText);
        assertEquals(-1, view.lastIso);
        assertEquals(2.0f, view.lastBrightnessGain, 0.0001f);
        assertEquals("×2.0", view.lastBrightnessMode);
        assertEquals(R.id.seekBarExposure, view.secondSeekBarId);
        assertEquals(0, view.secondSeekBarProgress);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<? super T>> Range<T> mockRange() {
        return mock(Range.class);
    }

    private static class FakeView implements CameraContract.View {
        int lastIso = Integer.MIN_VALUE;
        String lastExposureText = "";
        String lastExposureValue = "";
        String lastBrightnessMode = "";
        int lastSeekBarId = -1;
        int lastSeekBarProgress = -1;
        int secondSeekBarId = -1;
        int secondSeekBarProgress = -1;
        int seekBarSetCount = 0;
        int appliedIso = Integer.MIN_VALUE;
        long appliedExposure = -1;
        float lastBrightnessGain = -1f;

        @Override public void showLoading(boolean show) {}
        @Override public void updateCameraStatus(String status) {}
        @Override public void updatePhotoCount(int count) {}
        @Override public void showError(String error) {}
        @Override public void showToast(String message) {}
        @Override public void updateIsoDisplay(int iso) { lastIso = iso; }
        @Override public void updateExposureDisplay(String exposureText) { lastExposureText = exposureText; }
        @Override public void updateBrightnessMode(String mode) { lastBrightnessMode = mode; }
        @Override public void updateExposureValue(String exposureValue) { lastExposureValue = exposureValue; }
        @Override public void updateCenterLuminance(double lCdPerM2, double centerMeanDn,
                @Nullable String luminanceSourceTag) {}
        @Override public void onExposureRecommendationChanged(CameraModel.ExposureRecommendation recommendation) {}
        @Override public void requestAutoApplyRecommendation() {}
        @Override public void updateCalibrationStatus(String text) {}
        @Override public void updateCurveSource(String source) {}
        @Override public void updateCurveSourceBadge(String badgeText) {}
        @Override public void onDebevecGSaved() {}
        @Override public void onLevel1LookupUploadStateChanged() {}
        @Override public void onCalibrationLowQualityComplete() {}
        @Override public void onCalibrationAbnormalComplete() {}
        @Override public void onCalibrationHighMediumComplete(String qualityTier) {}
        @Override public void resetSeekBars() {}
        @Override
        public void setSeekBarProgress(int seekBarId, int progress) {
            seekBarSetCount++;
            if (seekBarSetCount == 1) {
                lastSeekBarId = seekBarId;
                lastSeekBarProgress = progress;
            } else {
                secondSeekBarId = seekBarId;
                secondSeekBarProgress = progress;
            }
        }
        @Override public void displayCapturedImage(Bitmap bitmap) {}
        @Override public void applyCameraIsoParameter(int iso) { appliedIso = iso; }
        @Override public void applyCameraExposureParameter(long exposure) { appliedExposure = exposure; }
        @Override public void setPreviewBrightness(float gain) { lastBrightnessGain = gain; }
        @Override public void setPreviewPseudoHueRange(float minBoostedN, float maxBoostedN) {}
        @Override public int getIsoProgress() { return 0; }
        @Override public int getExposureProgress() { return 0; }
        @Override public int getBrightnessProgress() { return 0; }
    }
}