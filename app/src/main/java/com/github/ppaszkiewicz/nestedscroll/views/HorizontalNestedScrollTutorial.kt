package com.github.ppaszkiewicz.nestedscroll.views

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.OverScroller
import androidx.core.content.res.use
import androidx.core.view.NestedScrollingChild3
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat
import androidx.core.view.children
import kotlin.math.absoluteValue

/**
 *  Reference on how to implement [NestedScrollingChild3].
 *
 *  For simplicity this does nothing aside from nested scrolling self on horizontal axis and begins
 *  scroll immediately on touch down instead of being an intercept event awaiting touch slope.
 *
 *  There are also no accessibility/keyboard events.
 */
class HorizontalNestedScrollTutorial @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0, defStyleRes: Int = 0
) : LinearLayout(context, attrs, defStyleAttr, defStyleRes), NestedScrollingChild3 {
    enum class Behavior(val alignScroll: Boolean, val alignFling: Boolean) {
        /** Free scroll and fling*/
        FREE_FREE(false, false),

        /** Free scroll (if TOUCH_UP doesn't cause a fling), align flings */
        FREE_ALIGN(false, true),

        /** Align scroll and flings */
        ALIGN_ALIGN(true, true)
    }

    companion object {
        // ******************************************
        // DEBUG behaviors- switch it if needed
        // scrolling behavior
        var BEHAVIOR: Behavior = Behavior.FREE_FREE

        // whether to ignore velocity if nested scrolling parents prescroll consumes scroll
        val PARENT_CONSUME_VELOCITY: Boolean = true

        // make fling heavier to execute if it aligns the next page
        val FLING_MUL
            get() = if (BEHAVIOR.alignFling) 3.0f else 1.0f
        // ******************************************
    }

    private val nestedScrollHelper = NestedScrollingChildHelper(this)

    val scroller = OverScroller(context)
    val minFlingVelocity: Float
    val maxFlingVelocity: Float

    /** If scroll is active */
    var isScrolling = false

    /** If last call to scroller was for a fling. */
    var isLastScrollerCallAFling = false

    /** Last touch events X position - always relative to this views local coordinates. */
    var lastX = 0

    /** Used for [computeScroll] calculation - track where [scroller] was. */
    var lastScrollerX = 0

    /** Current window offset since touch down that was caused by parent scrolling. */
    val nestedOffset = IntArray(2)

    /** Stores return value for window offset change caused by nested scroll*/
    val retScrollOffset = IntArray(2)

    /** Stores return value for consumed nested scroll. */
    val retConsumed = IntArray(2)

    // velocity tracker is only non null during scroll
    private var _vTracker: VelocityTracker? = null
    val vTracker: VelocityTracker
        get() = _vTracker!!

    init {
        val vc = ViewConfiguration.get(context)
        minFlingVelocity = vc.scaledMinimumFlingVelocity.toFloat()
        maxFlingVelocity = vc.scaledMaximumFlingVelocity.toFloat()

        setWillNotDraw(false)   // needed for overscroller ?
        // enable nested scrolling by default and handle android:nestedScrollingEnabled attribute
        var nestedEnabled = true
        if (attrs != null) {
            val nestedScrollingAttrs = IntArray(1) { android.R.attr.nestedScrollingEnabled }
            context.obtainStyledAttributes(attrs, nestedScrollingAttrs, defStyleAttr, defStyleRes)
                .use {
                    ViewCompat.saveAttributeDataForStyleable(
                        this, context, nestedScrollingAttrs, attrs, it, defStyleAttr, 0
                    )
                    nestedEnabled = it.getBoolean(0, true)
                }
        }
        isNestedScrollingEnabled = nestedEnabled
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // event X - in local coordinates relative to the view
        val evX = ev.x.toInt()
        // Velocity tracker is using motion events local coordinates (relative to this view).
        // To prevent nested scrolling parent from disrupting this calculation
        // use offset copy of touch event that remains unaffected by nested scroll
        val velocityEvent = MotionEvent.obtain(ev)
        velocityEvent.offsetLocation(nestedOffset[0].toFloat(), nestedOffset[1].toFloat())

        var includeVelocity = true // switch to exclude this events velocity from tracking

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // only 1 finger supported for now
                // nested scroll begins
                if (!isScrolling) {
                    scroller.computeScrollOffset()
                    if (!scroller.isFinished) {
                        scroller.abortAnimation()
                        stopNestedScroll(ViewCompat.TYPE_NON_TOUCH)
                    }
                    isScrolling = true
                    lastX = evX
                    _vTracker = VelocityTracker.obtain()
                    startNestedScroll(ViewCompat.SCROLL_AXIS_HORIZONTAL, ViewCompat.TYPE_TOUCH)
                    requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // movement
                if (isScrolling) {
                    // when nested scrolling lastX needs to remain anchored to this views local coordinates
                    // that means it must be offset whenever nested scrolling parent scrolls
                    var dx = lastX - evX    // diff in local coordinates since last scroll event
                    retConsumed[0] = 0
                    retConsumed[1] = 0
                    if (dispatchNestedPreScroll(
                            dx, 0,  // offsets
                            retConsumed, retScrollOffset, // return arrays
                            ViewCompat.TYPE_TOUCH   // type
                        )
                    ) {
                        dx -= retConsumed[0] // subtract what was consumed from amount to scroll by
                        nestedOffset[0] += retScrollOffset[0] // update nested offset by what was scrolled
                        // custom behavior here: ignore velocity of scrolling that happened in parent
                        if (PARENT_CONSUME_VELOCITY) {
                            velocityEvent.offsetLocation(retScrollOffset[0].toFloat(), 0f)
                        }
                    }
                    lastX = evX - retScrollOffset[0] // keep lastX anchored to local coordinates
                    // proceed with self scroll and dispatch nested scroll
                    doNestedScrollBy(dx, ViewCompat.TYPE_TOUCH)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (isScrolling) {
                    // nested scroll ends - prepare for a fling
                    stopNestedScroll(ViewCompat.TYPE_TOUCH)
                    vTracker.addMovement(velocityEvent)
                    vTracker.computeCurrentVelocity(1000, maxFlingVelocity)
                    val velocity = vTracker.xVelocity
                    // handle fling depending on test config
                    when (velocity.absoluteValue > (minFlingVelocity * FLING_MUL)) {
                        true -> when (BEHAVIOR.alignFling) {
                            true -> doFling(-velocity)  // notice that velocity is inverse
                            false -> doAlign(velocity < 0f) // align edge towards fling direction
                        }
                        false -> when (BEHAVIOR.alignScroll) {
                            true -> doAlign(scrollX <= maxScrollOffset()) // align to closer edge
                            false -> Unit   // unhandled, finish the scroll without fling
                        }
                    }
                    clearTouchEvent()
                    includeVelocity = false
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                // scrolling cancelled
                if (isScrolling) {
                    stopNestedScroll(ViewCompat.TYPE_TOUCH)
                    clearTouchEvent()
                }
            }


        }
        if (isScrolling && includeVelocity) {
            vTracker.addMovement(velocityEvent)
        }
        velocityEvent.recycle()
        return isScrolling
    }

    private fun clearTouchEvent() {
        vTracker.recycle()
        _vTracker = null
        isScrolling = false
        nestedOffset[0] = 0
        nestedOffset[1] = 0
    }

    /** Perform internal scroll and return how much actually was consumed. */
    private fun clampedScrollBy(dx: Int): Int {
        val maxScroll = maxScrollOffset()
        val target = scrollX + dx
        val clamped = target.coerceIn(0, maxScroll)
        val consumed = clamped - scrollX
        scrollTo(clamped, scrollY)
        return consumed   // how much is actually scrolled
    }

    /** Performs nested scroll (step) and returns unconsumed dx. */
    private fun doNestedScrollBy(dx: Int, type: Int): Int {
        // basic scrollTo of this view
        val consumedX = clampedScrollBy(dx)
        var unconsumedX = dx - consumedX

        retConsumed[0] = 0
        retConsumed[1] = 0
        dispatchNestedScroll(consumedX, 0, unconsumedX, 0, retScrollOffset, type, retConsumed)
        unconsumedX -= retConsumed[0] // scroll that's left over after parent consumed some

        if (retScrollOffset[0] != 0) { // view was scrolled due to parent scrolling
            lastX -= retScrollOffset[0] // keep lastX anchored to local coordinates
            nestedOffset[0] += retScrollOffset[0]
        }
        // now unconsumedX can be used to trigger the overscroll edge effects
        // pull glow ...

        if (!awakenScrollBars()) {
            invalidate()
        }
        return unconsumedX
    }

    /**
     * Perform a free fling using [velocity]. Returns `true` if fling was consumed.
     * */
    private fun doFling(velocity: Float): Boolean {
        if (dispatchNestedPreFling(velocity, 0f)) {
            return true // fling was consumed entirely by nested scrolling parent
        }
        val canScroll = canScrollHorizontally(velocity.toInt())
        // tell nested scrolling parent that this view is flinging
        dispatchNestedFling(velocity, 0f, canScroll)

        if (canScroll) {
            isLastScrollerCallAFling = true
            scroller.fling(
                scrollX, scrollY, // start
                velocity.toInt(), 0, // velocity
                0, maxScrollOffset(),   // x
                0, height,   // y
                0, 0 // overscroll
            )
            startNestedScroll(ViewCompat.SCROLL_AXIS_HORIZONTAL, ViewCompat.TYPE_NON_TOUCH)
            lastScrollerX = scrollX
            ViewCompat.postInvalidateOnAnimation(this) // this will trigger computeScroll
        }
        return canScroll
    }

    /**
     * Do a smooth scroll to align this views scroll. If [start] is true align to the start,
     * otherwise to the end.
     *
     * This works as a self scroll and does not participate in nested flinging or scrolling.
     * */
    private fun doAlign(start: Boolean) {
        val dx = if (start) maxScrollOffset() - scrollX else -scrollX
        if (dx == 0) return // nothing to scroll
        isLastScrollerCallAFling = false
        scroller.startScroll(
            scrollX, scrollY, // start
            dx, 0,   // diff
            500,   // duration
        )
        lastScrollerX = scrollX
        ViewCompat.postInvalidateOnAnimation(this) // this will trigger computeScroll
    }

    // this is invoked during every invalidate call
    override fun computeScroll() {
        // consume scroller state here
        if (scroller.isFinished) return
        scroller.computeScrollOffset()
        val targetX = scroller.currX
        var unconsumedX = targetX - lastScrollerX
        lastScrollerX = targetX

        if (isLastScrollerCallAFling) {
            // participate in nested scroll ONLY during a fling
            // since this is not a touch event there's no need to track offset in window
            retConsumed[0] = 0
            retConsumed[1] = 0
            dispatchNestedPreScroll(unconsumedX, 0, retConsumed, null, ViewCompat.TYPE_NON_TOUCH)
            unconsumedX -= retConsumed[0]

            // performs self and nested scroll
            if (unconsumedX != 0) {
                unconsumedX = doNestedScrollBy(unconsumedX, ViewCompat.TYPE_NON_TOUCH)
            }

            // if there's something unconsumed by doNestedScrollBy that means edge was reached
            // doScrollBy performs edge glows so here just finish scroll
            if (unconsumedX != 0) {
                scroller.abortAnimation()
                stopNestedScroll(ViewCompat.TYPE_NON_TOUCH)
            }
        } else {
            // alignment does not participate in nested scrolling, just scroll
            clampedScrollBy(unconsumedX)
            if (!awakenScrollBars()) {
                invalidate()
            }
        }

        if (!scroller.isFinished) {
            ViewCompat.postInvalidateOnAnimation(this) // keep scrolling
        } else {
            stopNestedScroll(ViewCompat.TYPE_NON_TOUCH) // scroller was finished
        }
    }

    // this should be used for "instant nested scroll jumps"
    /** Do an "end to end" nested scrolling by [dx]. */
    private fun nestedScrollByInternal(dx: Int, type: Int) {
        //stub:
        // dispatch start nested scroll
        // dispatch nested pre scroll
        // self scroll
        // dispatch nested scroll
        // dispatch end nested scroll
    }

    //left/right buttons could do a smooth scroll by doFling or doAlign
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return super.onKeyUp(keyCode, event)
    }

    // enables scrolling
    override fun computeHorizontalScrollRange(): Int {
        // not gonna support any margins or padding for this test
        return (children.lastOrNull()?.right ?: 0).coerceAtLeast(width)
    }

    fun maxScrollOffset() = computeHorizontalScrollRange() - computeHorizontalScrollExtent()

    // nestedscrollingchild implementation

    override fun startNestedScroll(axes: Int): Boolean {
        return nestedScrollHelper.startNestedScroll(axes)
    }

    override fun startNestedScroll(axes: Int, type: Int): Boolean {
        return nestedScrollHelper.startNestedScroll(axes, type)
    }

    override fun stopNestedScroll() {
        nestedScrollHelper.stopNestedScroll()
    }

    override fun stopNestedScroll(type: Int) {
        nestedScrollHelper.stopNestedScroll(type)
    }

    override fun hasNestedScrollingParent(): Boolean {
        return nestedScrollHelper.hasNestedScrollingParent()
    }

    override fun hasNestedScrollingParent(type: Int): Boolean {
        return nestedScrollHelper.hasNestedScrollingParent(type)
    }

    override fun isNestedScrollingEnabled(): Boolean {
        return nestedScrollHelper.isNestedScrollingEnabled
    }

    override fun setNestedScrollingEnabled(enabled: Boolean) {
        nestedScrollHelper.isNestedScrollingEnabled = enabled
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?
    ): Boolean {
        return nestedScrollHelper.dispatchNestedScroll(
            dxConsumed,
            dyConsumed,
            dxUnconsumed,
            dyUnconsumed,
            offsetInWindow
        )
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int,
        consumed: IntArray
    ) {
        nestedScrollHelper.dispatchNestedScroll(
            dxConsumed,
            dyConsumed,
            dxUnconsumed,
            dyUnconsumed,
            offsetInWindow,
            type,
            consumed
        )
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int
    ): Boolean {
        return nestedScrollHelper.dispatchNestedScroll(
            dxConsumed,
            dyConsumed,
            dxUnconsumed,
            dyUnconsumed,
            offsetInWindow,
            type
        )
    }

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?
    ): Boolean {
        return nestedScrollHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow)
    }

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?,
        type: Int
    ): Boolean {
        return nestedScrollHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)
    }

    override fun onDetachedFromWindow() {
        nestedScrollHelper.onDetachedFromWindow()
        super.onDetachedFromWindow()
    }
}