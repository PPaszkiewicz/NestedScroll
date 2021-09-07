package com.github.ppaszkiewicz.nestedscroll.util

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle

/**
 * Build and [Fragment.setArguments] of this fragment. Use only when constructing
 * fragment from within new instance.
 */
inline fun <T: Fragment>T.withArguments(argBuilder : Bundle.() -> Unit) = apply {
    check(lifecycle.currentState == Lifecycle.State.INITIALIZED)
    arguments = Bundle().apply(argBuilder)
}