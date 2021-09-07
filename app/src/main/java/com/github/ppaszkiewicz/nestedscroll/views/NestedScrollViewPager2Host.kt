package com.github.ppaszkiewicz.nestedscroll.views

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.res.use
import androidx.core.view.*
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.absoluteValue
import kotlin.math.sign

/**
 * Allows nested scrolling of content within [ViewPager2] to change its pages.
 *
 * If all pages contain exclusively nested scrolling views then set [isUserInputEnabled] to `false`.
 *
 * It's recommended that [ViewPager2.setOffscreenPageLimit] is at least 2 or nested scrolling might be interrupted.
 * */
open class NestedScrollViewPager2Host @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs), NestedScrollingParent3, NestedScrollingChild3 {
    companion object {
        const val TAG = "NestedScrollingViewPGRH"

        // (same as ViewPager2 constants) used as indexes in size 2 arrays
        const val HORIZONTAL = 0
        const val VERTICAL = 1
    }

    private val mParentHelper = NestedScrollingParentHelper(this)
    private val mChildHelper = NestedScrollingChildHelper(this)

    // reusable array for getOffsetInRec
    private val mChildOffset = IntArray(2)

    // reusable array of size 2 to use as return value container
    private val mReusableArray = IntArray(2)

    var isInNestedScroll = false
        private set
    private var mOrientation = HORIZONTAL
    private var mTouchOffset = 0 // collect clamped scroll
    private var canDoUserInput = false // value of isUserInputEnabled when nested scroll started

    /**
     * Value of internal [ViewPager2.isUserInputEnabled].
     *  - `true`: nested scrolling is not essential but it's possible to "fling over" entire unselected pages
     *  - `false` only if viewpager contains exclusively nested scrolling views in each page. Prevents flinging over unselected pages.
     * */
    var isUserInputEnabled: Boolean
        get() = viewPager.isUserInputEnabled
        set(value) {
            viewPager.isUserInputEnabled = value
        }

    val viewPager by lazy {
        getChildAt(0) as ViewPager2
    }

    val rec by lazy {
        viewPager.getChildAt(0) as RecyclerView
    }

    init {
        // enable nested scrolling by default and handle android:nestedScrollingEnabled attribute
        var nestedEnabled = true
        if (attrs != null) {
            val nestedScrollingAttrs = IntArray(1) { android.R.attr.nestedScrollingEnabled }
            context.obtainStyledAttributes(attrs, nestedScrollingAttrs, 0, 0)
                .use {
                    ViewCompat.saveAttributeDataForStyleable(
                        this, context, nestedScrollingAttrs, attrs, it, 0, 0
                    )
                    nestedEnabled = it.getBoolean(0, true)
                }
        }
        isNestedScrollingEnabled = nestedEnabled
    }

    // ** MAIN NESTED SCROLL LOGIC **

    private fun doStartNestedScroll(child: View, target: View, nestedScrollAxes: Int): Boolean {
        return when {
            target === rec -> false // do not accept nested scrolling requests that come from viewpager itself
            nestedScrollAxes == ViewGroup.SCROLL_AXIS_HORIZONTAL && viewPager.orientation == ViewPager2.ORIENTATION_HORIZONTAL -> {
                mOrientation = HORIZONTAL
                true
            }
            nestedScrollAxes == ViewGroup.SCROLL_AXIS_VERTICAL && viewPager.orientation == ViewPager2.ORIENTATION_VERTICAL -> {
                mOrientation = VERTICAL
                true
            }
            else -> false
        }
    }

    private fun doNestedScrollAccepted(child: View, target: View, axes: Int) {
        // nested scrolling parent and child behavior
        mParentHelper.onNestedScrollAccepted(child, target, axes)
        startNestedScroll(axes)

        // disallow viewpager from reacting to this scroll, it will be fed fakeDrags instead
        rec.requestDisallowInterceptTouchEvent(true)
        viewPager.beginFakeDrag()
        if (viewPager.offscreenPageLimit < 2) {
            Log.w(
                TAG,
                "ViewPager2 should have offscreenPageLimit of at least 2 or nested scrolling might be interrupted, current: ${viewPager.offscreenPageLimit}"
            )
        }

        // local state
        mTouchOffset = 0
        isInNestedScroll = true
        canDoUserInput = isUserInputEnabled
        isUserInputEnabled = true // raise or fake drag doesn't work
    }

    private fun doStopNestedScroll(child: View) {
        mParentHelper.onStopNestedScroll(child)
        stopNestedScroll()
        viewPager.endFakeDrag()
        isUserInputEnabled = canDoUserInput
        isInNestedScroll = false
    }

    private fun doNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) {
        // dispatch nested pre scroll upwards (child behavior)
        dispatchNestedPreScroll(dx, dy, mReusableArray.clear(), null)
        consumed.add(mReusableArray)

        // pixels to drag relevant for viewpager orientation
        val px = getPx(dx - consumed[0], dy - consumed[1])
        if (px == 0) return // nothing to do

        val targetCanScroll = canScroll(target, px)
        val offset = getPageOffsetFor(target)[mOrientation]
        if (offset != 0) {
            // if viewpager is in the middle of dragging between the pages
            // and user reverses drag direction scroll viewpager before target:
            if (targetCanScroll && offset.sign == px.sign) {
                if (mTouchOffset != 0) {
                    // this happens if viewpager is smaller than touchable area (doesn't fit the screen)
                    // consume or clamp the scroll until touch is back at where it started
                    val preClamp = mTouchOffset
                    mTouchOffset += px.coerceAbs(mTouchOffset)
                    consumed[mOrientation] += mTouchOffset - preClamp
                }
                doFakeDragBy(
                    target,
                    (px - consumed[mOrientation]).coerceAbs(offset), // account for consumed scroll
                    mReusableArray,
                    offset
                )
                consumed.add(mReusableArray)
            }
        }
    }

    private fun doNestedScroll(
        target: View,
        dxConsumed: Int, dyConsumed: Int, // unused (how much child scrolled is irrelevant)
        dxUnconsumed: Int, dyUnconsumed: Int,
        type: Int, consumed: IntArray?
    ) {
        // all unused scroll goes to viewpager to scroll or fling between pages
        val pxUnconsumed = getPx(dxUnconsumed, dyUnconsumed)
        if (pxUnconsumed == 0) {
            // no movement can be done, tell that to parent
            dispatchNestedScroll(
                0,
                0,
                dxUnconsumed,
                dyUnconsumed,
                null,
                type,
                mReusableArray.clear()
            )
            consumed?.add(mReusableArray)
            return
        }

        // scrolling into another page
        // how much current page is offset from viewpagers center
        val offset = getPageOffsetFor(target)[mOrientation]
        // assume that pxUnconsumed sign is ALWAYS different than offsets
        // this calculates how much viewpager needs to scroll to move target exactly out of bounds and
        // align next page
        val clamped = pxUnconsumed.coerceAbs(offset + (getRecSize() * pxUnconsumed.sign))
        // this is how much touch will be consumed without actually scrolling the viewpager
        // prevents scrolling through multiple pages so fling won't occur and user has to
        // touch down on next page to start scrolling it
        mTouchOffset += pxUnconsumed - clamped

        doFakeDragBy(target, clamped, mReusableArray, offset)
        consumed?.add(mReusableArray)

        if (mTouchOffset != 0) {
            // in process of clamping the touch (see mTouchOffset above)
            // consider entire scroll consumed and dispatch that to nested scrolling parent (if exists)
            // so it doesn't perform any further scrolling either
            // mReusableArray holds how much was scrolled by doFakeDragBy so just overwrite value on relevant axis
            when (mOrientation) {
                HORIZONTAL -> {
                    mReusableArray[0] = dxUnconsumed
                    consumed?.set(0, dxUnconsumed)
                }
                VERTICAL -> {
                    mReusableArray[1] = dyUnconsumed
                    consumed?.set(1, dyUnconsumed)
                }
            }
        } else {
            // scrolling between pages or overscrolling into the very edge is occurring
        }

        dispatchNestedScroll(
            mReusableArray[0],
            mReusableArray[1],
            dxUnconsumed - mReusableArray[0],
            dyUnconsumed - mReusableArray[1],
            null, type,
            mReusableArray.clear()
        )
        consumed?.add(mReusableArray)
    }

    /**
     * Drag viewpager using fake drag.
     *
     * @param target view within viewpager page that initiated nested scroll
     * @param pixelOffset how much to scroll
     * @param scrolledOutput how much viewpager actually scrolled (overwrites values)
     * @param targetOffset optional; precalculated [getPageOffsetFor] for [target]
     * */
    private fun doFakeDragBy(
        target: View,
        pixelOffset: Int,
        scrolledOutput: IntArray,
        targetOffset: Int? = null
    ) {
        if (pixelOffset == 0) return // quick exit
        // track how much scroll is consumed so target can show overscroll
        // there isn't a good method to see how much fakeDragBy consumes so just track targets offset
        var scrl = targetOffset ?: getPageOffsetFor(target)[mOrientation]
        if (!viewPager.fakeDragBy(-pixelOffset.toFloat())) {
            Log.e(TAG, "fake drag failed by $pixelOffset")
        }
        scrl = (scrl - getPageOffsetFor(target)[mOrientation]).coerceAbs(pixelOffset)
        scrolledOutput[mOrientation] = scrl
        scrolledOutput[(mOrientation + 1) % 2] = 0
    }

    /** Calculate offset of page hosting [view]. */
    private fun getPageOffsetFor(view: View): IntArray {
        mChildOffset.clear()
        // this happens when there's not enough offscreenPageLimit and user keeps touching view
        // that gets detached due to leaving the viewport
        if (!view.isAttachedToWindow) {
            Log.e(TAG, "${view.l} is not attached!")
            // even though [0, 0] is returned it doesn't matter because detaching target view will
            // interrupt nested scrolling
            return mChildOffset
        }
        val pageRoot = getPageRoot(view)
        // viewpager doesn't use margins or paddings for its internal pages roots
        mChildOffset[0] = pageRoot.left// - pageRoot.marginLeft + rec.paddingLeft
        mChildOffset[1] = pageRoot.top// - pageRoot.marginTop + rec.paddingTop
        return mChildOffset
    }

    private tailrec fun getPageRoot(view: View): View {
        return if (view.parent === rec) view else getPageRoot(view.parent as View)
    }

    // orientation helpers
    private fun getRecSize() = when (mOrientation) {
        HORIZONTAL -> rec.width
        else -> rec.height
    }

    private fun getPx(dx: Int, dy: Int) = when (mOrientation) {
        HORIZONTAL -> dx
        else -> dy
    }

    private fun canScroll(view: View, px: Int) = when (mOrientation) {
        HORIZONTAL -> view.canScrollHorizontally(px)
        else -> view.canScrollVertically(px)
    }

    // coerce within absolute value
    private fun Int.coerceAbs(maxVal: Int) = maxVal.absoluteValue.let { coerceIn(-it, it) }


    /** only for size 2 array: change values to zeroes and return self */
    private fun IntArray.clear() = apply {
        this[0] = 0
        this[1] = 0
    }

    /** Only for size 2 array: add each value of [other] into this. */
    private fun IntArray.add(other: IntArray) = apply {
        this[0] += other[0]
        this[1] += other[1]
    }

    // some nested scrolling parents check those so delegate it to viewpager
    override fun canScrollHorizontally(direction: Int) = viewPager.canScrollHorizontally(direction)
    override fun canScrollVertically(direction: Int) = viewPager.canScrollVertically(direction)

    // ** NestedScrollingParent, NestedScrollingParent2 and NestedScrollingParent3 ** /

    // NestedScrollingParent
    override fun onNestedScrollAccepted(child: View, target: View, axes: Int) =
        doNestedScrollAccepted(child, target, axes)

    override fun onStopNestedScroll(child: View) = doStopNestedScroll(child)

    override fun getNestedScrollAxes() = mParentHelper.nestedScrollAxes
    override fun onStartNestedScroll(child: View, target: View, nestedScrollAxes: Int) =
        doStartNestedScroll(child, target, nestedScrollAxes)

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int
    ) = doNestedScroll(
        target,
        dxConsumed, dyConsumed,
        dxUnconsumed, dyUnconsumed,
        ViewCompat.TYPE_TOUCH, null
    )

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) =
        doNestedPreScroll(target, dx, dy, consumed)

    override fun onNestedFling(
        target: View, velocityX: Float, velocityY: Float, consumed: Boolean
    ) = dispatchNestedFling(velocityX, velocityY, consumed) // unhandled: just propagate to parent

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float) =
        dispatchNestedPreFling(velocityX, velocityY)    // unhandled: just propagate to parent

    // NestedScrollingParent2
    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) =
        doNestedScrollAccepted(child, target, axes)

    override fun onStopNestedScroll(target: View, type: Int) = doStopNestedScroll(target)

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int) =
        doStartNestedScroll(child, target, axes)

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int,
        type: Int
    ) = doNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, null)

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) =
        doNestedPreScroll(target, dx, dy, consumed)

    // NestedScrollingParent3
    override fun onNestedScroll(
        target: View,
        dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int,
        type: Int, consumed: IntArray
    ) = doNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, consumed)

    // ** NestedScrollingChild, NestedScrollingChild2 and NestedScrollingChild3 ** /

    // NestedScrollingChild
    override fun setNestedScrollingEnabled(enabled: Boolean) {
        mChildHelper.isNestedScrollingEnabled = enabled
    }

    override fun isNestedScrollingEnabled() = mChildHelper.isNestedScrollingEnabled
    override fun startNestedScroll(axes: Int) = startNestedScroll(axes, ViewCompat.TYPE_TOUCH)
    override fun stopNestedScroll() = stopNestedScroll(ViewCompat.TYPE_TOUCH)
    override fun hasNestedScrollingParent() = hasNestedScrollingParent(ViewCompat.TYPE_TOUCH)
    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?
    ) = mChildHelper.dispatchNestedScroll(
        dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed,
        offsetInWindow
    )

    override fun dispatchNestedPreScroll(
        dx: Int, dy: Int, consumed: IntArray?, offsetInWindow: IntArray?
    ) = dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, ViewCompat.TYPE_TOUCH)

    override fun dispatchNestedFling(velocityX: Float, velocityY: Float, consumed: Boolean) =
        mChildHelper.dispatchNestedFling(velocityX, velocityY, consumed)

    override fun dispatchNestedPreFling(velocityX: Float, velocityY: Float) =
        mChildHelper.dispatchNestedPreFling(velocityX, velocityY)

    // NestedScrollingChild2
    override fun startNestedScroll(axes: Int, type: Int) =
        mChildHelper.startNestedScroll(axes, type)

    override fun stopNestedScroll(type: Int) = mChildHelper.stopNestedScroll(type)
    override fun hasNestedScrollingParent(type: Int) = mChildHelper.hasNestedScrollingParent(type)
    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?, type: Int
    ) = mChildHelper.dispatchNestedScroll(
        dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed,
        offsetInWindow, type
    )

    override fun dispatchNestedPreScroll(
        dx: Int, dy: Int, consumed: IntArray?, offsetInWindow: IntArray?, type: Int
    ) = mChildHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)

    // NestedScrollingChild3
    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int,
        dyUnconsumed: Int, offsetInWindow: IntArray?, type: Int, consumed: IntArray
    ) = mChildHelper.dispatchNestedScroll(
        dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed,
        offsetInWindow, type, consumed
    )

    // logging helper
    private val View.l
        get() = "${this::class.java.simpleName} $tag"
}