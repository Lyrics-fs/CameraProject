package com.example.camera.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.widget.LinearLayout
import kotlin.math.min

/**
 * 纵向 [LinearLayout]，可限制最大高度（用于左下控制外壳，与左上角 HUD 留白对齐）；本身不滚动。
 */
class MaxHeightLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private var maxHeightPx: Int = -1

    fun setMaxHeightPx(px: Int) {
        val v = if (px > 0) px else -1
        if (v == maxHeightPx) return
        maxHeightPx = v
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxHeightPx > 0) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val capped = when (mode) {
                MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
                MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(min(size, maxHeightPx), MeasureSpec.AT_MOST)
                MeasureSpec.EXACTLY -> MeasureSpec.makeMeasureSpec(min(size, maxHeightPx), MeasureSpec.EXACTLY)
                else -> heightMeasureSpec
            }
            super.onMeasure(widthMeasureSpec, capped)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
