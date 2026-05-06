package com.example.camera.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.camera.R
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 预览上灰卡选区：单指拖矩形体移动、四角手柄自由缩放；归一化坐标见 [getSelectedRectNormalized]。
 */
class GreyCardSelector @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private enum class DragKind { NONE, MOVE, TL, TR, BR, BL }

    private val selection = RectF()
    private var hasSelection = false

    private var dragKind = DragKind.NONE
    private val rectAtDown = RectF()
    private var downX = 0f
    private var downY = 0f
    private var lastMoveX = 0f
    private var lastMoveY = 0f

    private val strokePx: Float
    private val handleRadiusPx: Float
    private val handleHitExtraPx: Float
    private val minSidePx: Float
    private val dashOnPx: Float
    private val dashOffPx: Float

    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.grey_card_selector_stroke)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 33, 150, 243)
        style = Paint.Style.FILL
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    init {
        val dm = resources.displayMetrics
        strokePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.5f, dm)
        handleRadiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5f, dm)
        handleHitExtraPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, dm)
        minSidePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36f, dm)
        dashOnPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, dm)
        dashOffPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, dm)
        rectPaint.strokeWidth = strokePx
        rectPaint.pathEffect = DashPathEffect(floatArrayOf(dashOnPx, dashOffPx), 0f)
    }

    /**
     * 进入圈选：默认画面约 1/4 面积（边长约 50%×50%）居中；若 [previousNormalized] 有效则以其为初值。
     */
    fun enterEditMode(previousNormalized: RectF?) {
        visibility = VISIBLE
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            post { enterEditMode(previousNormalized) }
            return
        }
        val r = buildInitialRect(previousNormalized, w, h)
        selection.set(r)
        hasSelection = true
        dragKind = DragKind.NONE
        invalidate()
    }

    private fun buildInitialRect(prev: RectF?, w: Int, h: Int): RectF {
        if (prev != null && prev.width() > 0.01f && prev.height() > 0.01f) {
            var l = prev.left * w
            var t = prev.top * h
            var r = prev.right * w
            var b = prev.bottom * h
            if (l > r) {
                val x = l
                l = r
                r = x
            }
            if (t > b) {
                val x = t
                t = b
                b = x
            }
            l = l.coerceIn(0f, (w - 1).toFloat())
            t = t.coerceIn(0f, (h - 1).toFloat())
            r = r.coerceIn(l + minSidePx, w.toFloat())
            b = b.coerceIn(t + minSidePx, h.toFloat())
            return RectF(l, t, r, b)
        }
        val rw = w * 0.5f
        val rh = h * 0.5f
        val left = (w - rw) / 2f
        val top = (h - rh) / 2f
        return RectF(left, top, left + rw, top + rh)
    }

    /** 相对本 View 的 **0～1** 归一化矩形；无效时 **null**。 */
    fun getSelectedRectNormalized(): RectF? {
        if (!hasSelection || width <= 0 || height <= 0) return null
        if (selection.width() < 4f || selection.height() < 4f) return null
        return RectF(
            selection.left / width,
            selection.top / height,
            selection.right / width,
            selection.bottom / height,
        )
    }

    /** 清除选区（隐藏或重置时调用）。 */
    fun clearSelection() {
        hasSelection = false
        dragKind = DragKind.NONE
        invalidate()
    }

    private fun hitKind(x: Float, y: Float): DragKind {
        if (!hasSelection) return DragKind.NONE
        val hr = handleRadiusPx + handleHitExtraPx
        val cx = floatArrayOf(selection.left, selection.right, selection.right, selection.left)
        val cy = floatArrayOf(selection.top, selection.top, selection.bottom, selection.bottom)
        for (i in 0..3) {
            if (hypot((x - cx[i]).toDouble(), (y - cy[i]).toDouble()) <= hr) {
                return when (i) {
                    0 -> DragKind.TL
                    1 -> DragKind.TR
                    2 -> DragKind.BR
                    else -> DragKind.BL
                }
            }
        }
        if (x >= selection.left && x <= selection.right && y >= selection.top && y <= selection.bottom) {
            return DragKind.MOVE
        }
        return DragKind.NONE
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasSelection) return false
        val ex = event.x
        val ey = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragKind = hitKind(ex, ey)
                if (dragKind == DragKind.NONE) return false
                if (dragKind != DragKind.MOVE) {
                    rectAtDown.set(selection)
                }
                downX = ex
                downY = ey
                lastMoveX = ex
                lastMoveY = ey
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragKind == DragKind.NONE) return false
                when (dragKind) {
                    DragKind.MOVE -> {
                        val dx = ex - lastMoveX
                        val dy = ey - lastMoveY
                        lastMoveX = ex
                        lastMoveY = ey
                        selection.offset(dx, dy)
                        clampMoveIntoView()
                    }
                    DragKind.TL -> resizeFromDown(ex, ey, moveLeft = true, moveTop = true)
                    DragKind.TR -> resizeFromDown(ex, ey, moveRight = true, moveTop = true)
                    DragKind.BR -> resizeFromDown(ex, ey, moveRight = true, moveBottom = true)
                    DragKind.BL -> resizeFromDown(ex, ey, moveLeft = true, moveBottom = true)
                    else -> {}
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragKind = DragKind.NONE
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun resizeFromDown(
        ex: Float,
        ey: Float,
        moveLeft: Boolean = false,
        moveTop: Boolean = false,
        moveRight: Boolean = false,
        moveBottom: Boolean = false,
    ) {
        val dx = ex - downX
        val dy = ey - downY
        var l = rectAtDown.left
        var t = rectAtDown.top
        var r = rectAtDown.right
        var b = rectAtDown.bottom
        if (moveLeft) l = rectAtDown.left + dx
        if (moveTop) t = rectAtDown.top + dy
        if (moveRight) r = rectAtDown.right + dx
        if (moveBottom) b = rectAtDown.bottom + dy
        normalizeAndClamp(l, t, r, b)
    }

    private fun normalizeAndClamp(l: Float, t: Float, r: Float, b: Float) {
        var left = min(l, r)
        var right = max(l, r)
        var top = min(t, b)
        var bottom = max(t, b)
        val w = width.toFloat()
        val h = height.toFloat()
        if (right - left < minSidePx) {
            when {
                left <= 0.001f -> {
                    right = left + minSidePx
                }
                right >= w - 0.001f -> {
                    left = right - minSidePx
                }
                else -> {
                    val mid = (left + right) / 2f
                    left = mid - minSidePx / 2f
                    right = mid + minSidePx / 2f
                }
            }
        }
        if (bottom - top < minSidePx) {
            when {
                top <= 0.001f -> bottom = top + minSidePx
                bottom >= h - 0.001f -> top = bottom - minSidePx
                else -> {
                    val mid = (top + bottom) / 2f
                    top = mid - minSidePx / 2f
                    bottom = mid + minSidePx / 2f
                }
            }
        }
        left = left.coerceIn(0f, w - minSidePx)
        top = top.coerceIn(0f, h - minSidePx)
        right = right.coerceIn(left + minSidePx, w)
        bottom = bottom.coerceIn(top + minSidePx, h)
        selection.set(left, top, right, bottom)
    }

    private fun clampMoveIntoView() {
        var dx = 0f
        var dy = 0f
        if (selection.left < 0f) dx = -selection.left
        if (selection.top < 0f) dy = -selection.top
        if (selection.right > width.toFloat()) dx = width.toFloat() - selection.right
        if (selection.bottom > height.toFloat()) dy = height.toFloat() - selection.bottom
        selection.offset(dx, dy)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasSelection) return
        canvas.drawRect(selection, fillPaint)
        canvas.drawRect(selection, rectPaint)
        val corners = floatArrayOf(
            selection.left, selection.top,
            selection.right, selection.top,
            selection.right, selection.bottom,
            selection.left, selection.bottom,
        )
        var i = 0
        while (i < corners.size) {
            canvas.drawCircle(corners[i], corners[i + 1], handleRadiusPx, handlePaint)
            i += 2
        }
    }

    fun hideSelector() {
        visibility = GONE
        clearSelection()
    }
}
