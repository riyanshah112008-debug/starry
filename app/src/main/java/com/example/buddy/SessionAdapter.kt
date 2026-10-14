package com.example.buddy

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.buddy.data.model.SessionRow
import com.example.buddy.databinding.ItemSessionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** History drawer list: one row per conversation. */
class SessionAdapter(
    private val onClick: (SessionRow) -> Unit,
    private val onLongClick: (SessionRow) -> Unit
) : ListAdapter<SessionRow, SessionAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val b: ItemSessionBinding) : RecyclerView.ViewHolder(b.root) {
        private val format = SimpleDateFormat("d MMM", Locale.getDefault())

        fun bind(row: SessionRow) {
            val title = row.title?.take(60)?.trim().orEmpty()
            b.tvSessionTitle.text = title.ifEmpty {
                b.root.context.getString(R.string.untitled_chat)
            }
            b.tvSessionTime.text = format.format(Date(row.updatedAt))
            b.root.setOnClickListener { onClick(row) }
            b.root.setOnLongClickListener {
                onLongClick(row)
                true
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<SessionRow>() {
        override fun areItemsTheSame(oldItem: SessionRow, newItem: SessionRow) =
            oldItem.sessionId == newItem.sessionId

        override fun areContentsTheSame(oldItem: SessionRow, newItem: SessionRow) =
            oldItem == newItem
    }
}
