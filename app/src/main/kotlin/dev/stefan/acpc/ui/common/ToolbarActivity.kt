package dev.stefan.acpc.ui.common

import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors

/**
 * Activity with a toolbar above its content and the whole thing laid out
 * between the system bars. Subclasses call [setContentView] as usual; the
 * toolbar becomes the support action bar with the Up arrow enabled.
 */
abstract class ToolbarActivity : AppCompatActivity() {

    override fun setContentView(layoutResID: Int) {
        setContentView(layoutInflater.inflate(layoutResID, null))
    }

    override fun setContentView(view: View?) {
        if (view == null) { super.setContentView(null); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this)
        toolbar.setBackgroundColor(MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorSurface))
        val tv = TypedValue()
        theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, tv, true)
        val height = TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
        root.addView(toolbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))
        root.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        super.setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        EdgeToEdge.padSystemBars(root)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
