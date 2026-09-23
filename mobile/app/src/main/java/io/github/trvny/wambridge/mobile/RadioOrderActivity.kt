package io.github.trvny.wambridge.mobile

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

class RadioOrderActivity : Activity() {
    private val store by lazy { RadioStationStore(this) }
    private val stations = mutableListOf<MobileRadioStation>()
    private lateinit var adapter: StationOrderAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileUi.applyWindow(this)

        stations += store.all()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.wam_background))
            setPadding(
                MobileUi.dp(this@RadioOrderActivity, 18),
                MobileUi.dp(this@RadioOrderActivity, 18),
                MobileUi.dp(this@RadioOrderActivity, 18),
                MobileUi.dp(this@RadioOrderActivity, 18),
            )
        }

        root.addView(
            MobileUi.header(
                this,
                "Station order",
                "Hold a station and drag it. New stations from station_packs.json append without disturbing your order.",
            ),
        )

        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@RadioOrderActivity)
            overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        adapter = StationOrderAdapter(stations) { station ->
            if (store.isPinned(station.alias)) "Pinned · hold and drag" else "Hold and drag"
        }
        recycler.adapter = adapter

        val helper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                0,
            ) {
                override fun isLongPressDragEnabled(): Boolean = true

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    val from = viewHolder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                    Collections.swap(stations, from, to)
                    adapter.notifyItemMoved(from, to)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    store.saveOrder(stations.map { it.alias })
                }
            },
        )
        helper.attachToRecyclerView(recycler)

        root.addView(
            recycler,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply { topMargin = MobileUi.dp(this@RadioOrderActivity, 12) },
        )
        root.addView(
            MobileUi.button(this, "Done", MobileUi.ButtonKind.PRIMARY) {
                store.saveOrder(stations.map { it.alias })
                finish()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = MobileUi.dp(this@RadioOrderActivity, 12) }
            },
        )
        setContentView(root)
    }

    private class StationOrderAdapter(
        private val stations: List<MobileRadioStation>,
        private val subtitle: (MobileRadioStation) -> String,
    ) : RecyclerView.Adapter<StationOrderAdapter.Holder>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long =
            stations[position].alias.lowercase().hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val card = MobileUi.card(context).apply {
                isLongClickable = true
                minimumHeight = MobileUi.dp(context, 72)
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(context).apply {
                textSize = 17f
                setTextColor(context.getColor(R.color.wam_text))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val meta = MobileUi.body(context, "")
            card.addView(title)
            card.addView(meta)
            return Holder(card, title, meta)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val station = stations[position]
            holder.title.text = station.alias
            holder.meta.text = subtitle(station)
        }

        override fun getItemCount(): Int = stations.size

        class Holder(
            root: LinearLayout,
            val title: TextView,
            val meta: TextView,
        ) : RecyclerView.ViewHolder(root)
    }
}
