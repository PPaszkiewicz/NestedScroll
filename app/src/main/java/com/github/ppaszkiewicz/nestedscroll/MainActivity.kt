package com.github.ppaszkiewicz.nestedscroll

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.ppaszkiewicz.nestedscroll.MainActivity.PageFactory
import com.github.ppaszkiewicz.nestedscroll.databinding.ActivityMainBinding
import com.github.ppaszkiewicz.nestedscroll.util.viewBinding

class MainActivity : AppCompatActivity() {
    val views by viewBinding<ActivityMainBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views.layPager2.adapter = NestAdapter { RecyclerFragment.newInstance(it) }
        views.layPager2.offscreenPageLimit = 2
        views.layPagerHost.tag = "outer"
        views.layPagerHost.isUserInputEnabled = false
        views.txtDebug.text = "RecyclerFragment"
        views.root.updatePadding(left = 100, right = 100)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val factory: PageFactory = when (item.itemId) {
            R.id.textFragment -> PageFactory { TextFragment.newInstance(it) }
            R.id.recyclerFragment -> PageFactory { RecyclerFragment.newInstance(it) }
            R.id.tutorialFragment -> PageFactory { TutorialFragment.newInstance(it) }
            R.id.viewPagerTextFragment -> PageFactory {
                ViewPagerTextFragment.newInstance(it, true)
            }
            R.id.viewPagerRecyclerFragment -> PageFactory { ViewPagerRecyclerFragment.newInstance(it) }
            else -> return super.onOptionsItemSelected(item)
        }
        // textFragment is the only one that's not driven by nested scrolling
        views.layPagerHost.isUserInputEnabled = item.itemId == R.id.textFragment
        views.txtDebug.text = item.title
        views.layPager2.adapter = NestAdapter(factory)
        return true
    }

    // Switch created fragment:
    // TextFragment - non-nested scrolling element
    // RecyclerFragment - primary test, see how recycler with nested scrolling behaves
    // TutorialFragment - similar to RecyclerFragment but uses custom nested scrolling impl
    // ViewPagerTextFragment - wrapped viewpager with TextFragments
    // ViewPagerRecyclerFragment - wrapped viewpager with RecyclerFragments. Scrolling can be done only
    //                              by dragging recyclerViews

    private fun interface PageFactory {
        fun createFragment(position: Int): Fragment
    }

    private inner class NestAdapter(val pageFactory: PageFactory) :
        FragmentStateAdapter(this) {
        override fun getItemCount() = 3 // 3 pages?
        override fun createFragment(position: Int) = pageFactory.createFragment(position)
    }
}