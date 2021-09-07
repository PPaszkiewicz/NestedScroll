package com.github.ppaszkiewicz.nestedscroll


import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EdgeEffect
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.ppaszkiewicz.nestedscroll.databinding.ActivityMainBinding
import com.github.ppaszkiewicz.nestedscroll.databinding.BigTextBinding
import com.github.ppaszkiewicz.nestedscroll.databinding.TutorialScrollBinding
import com.github.ppaszkiewicz.nestedscroll.util.viewBinding
import com.github.ppaszkiewicz.nestedscroll.util.withArguments

/** Content of viewpager - recyclerview. */
class RecyclerFragment : Fragment(R.layout.recycler) {
    companion object {
        const val FRAG = "FRAG"
        const val NUM = "NUM"
        fun newInstance(num: Int) = RecyclerFragment().withArguments {
            putInt(NUM, num)
        }
    }

    val recycler
        get() = requireView() as RecyclerView

    val num by lazy {
        requireArguments().getInt(NUM)
    }

    val adapt by lazy {
        NestedRecyclerAdapter(num)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(FRAG, "CREATED $num")
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler.layoutManager =
            LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        PagerSnapHelper().attachToRecyclerView(recycler)
        recycler.adapter = adapt
        recycler.tag = "[[$num]]"
        recycler.setHasFixedSize(true)
        recycler.setBackgroundResource(R.color.black_transp)
        recycler.edgeEffectFactory = object : RecyclerView.EdgeEffectFactory() {
            override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
                return EdgeEffect(view.context).apply {
                    color = Color.RED
                }
            }
        }
        view.updatePadding(bottom = 100) // make some padding for bottom to test non recycler scroll
    }


    class NestedRecyclerAdapter(val num: Int) :
        RecyclerView.Adapter<NestedRecyclerAdapter.Holder>() {
        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): NestedRecyclerAdapter.Holder {
            return Holder(
                BigTextBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }

        override fun onBindViewHolder(holder: NestedRecyclerAdapter.Holder, position: Int) {
            with(holder.views) {
                bigText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    matchConstraintPercentHeight = 0.8f - (0.2f * position)
                }
                bigText.text = "$num - $position"
            }
        }

        override fun getItemCount() = 3 // don't need more for now

        class Holder(val views: BigTextBinding) : RecyclerView.ViewHolder(views.root) {

        }
    }
}

/** Page for fragment that hosts viewpager (nested content of activity). */
abstract class ViewPagerFragment : Fragment(R.layout.activity_main) {
    companion object {
        const val NUM = "NUM"
        const val INPUT_ENABLED = "INPUT_ENABLED"
    }

    abstract fun createPage(position: Int): Fragment

    val views by viewBinding<ActivityMainBinding>()

    val num by lazy {
        requireArguments().getInt(NUM)
    }
    val adapter by lazy {
        object : FragmentStateAdapter(this) {
            override fun getItemCount() = 3 // 3 pages?

            override fun createFragment(position: Int): Fragment {
                return createPage(position)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        views.txtDebug.text = "NestedViewPager $num"
        views.layPager2.adapter = adapter
        views.layPager2.offscreenPageLimit = 2
        views.layPagerHost.isUserInputEnabled = false
    }
}

/** Fragment that hosts viewpager with [TextFragment]. */
class ViewPagerTextFragment : ViewPagerFragment() {
    companion object {
        /**
         * @param num page number to display
         * @param inputEnabled if internal pagerHost should have enabled input - disable if pages contain only nested scrolling elements
         * */
        fun newInstance(num: Int, inputEnabled: Boolean) = ViewPagerTextFragment().withArguments {
            putInt(NUM, num)
            putBoolean(INPUT_ENABLED, inputEnabled)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        views.layPagerHost.isUserInputEnabled = requireArguments().getBoolean(INPUT_ENABLED)
    }

    override fun createPage(position: Int) = TextFragment.newInstance(position)
}

/** Fragment that hosts viewpager with [RecyclerFragment]. */
class ViewPagerRecyclerFragment : ViewPagerFragment() {
    companion object {
        fun newInstance(num: Int) = ViewPagerRecyclerFragment().withArguments {
            putInt(NUM, num)
        }
    }

    override fun createPage(position: Int) = RecyclerFragment.newInstance(position)
}

/** Simple text fragment. */
class TextFragment : Fragment(R.layout.big_text) {
    companion object {
        const val NUM = "NUM"
        fun newInstance(num: Int) = TextFragment().withArguments {
            putInt(NUM, num)
        }
    }

    val views by viewBinding<BigTextBinding>()
    val num by lazy {
        requireArguments().getInt(RecyclerFragment.NUM)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        views.bigText.text = "$num"
    }
}

/** Hosts [HorizontalNestedScrollTutorial]. */
class TutorialFragment : Fragment(R.layout.tutorial_scroll) {
    val views by viewBinding<TutorialScrollBinding>()

    companion object {
        const val NUM = "NUM"
        fun newInstance(num: Int) = TutorialFragment().withArguments {
            putInt(NUM, num)
        }
    }

    val num by lazy {
        requireArguments().getInt(RecyclerFragment.NUM)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        views.bigText1.text = "<$num"
        views.bigText2.text = "$num>"
    }
}