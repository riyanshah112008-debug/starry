package com.example.buddy

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.buddy.data.entity.ChatMessage
import com.example.buddy.data.repo.ChatRepository
import com.example.buddy.databinding.ItemMsgAssistantBinding
import com.example.buddy.databinding.ItemMsgStatusBinding
import com.example.buddy.databinding.ItemMsgUserBinding
import com.example.buddy.security.SecureVault

/**
 * ChatGPT-style message list:
 * - your messages ride on the right in a soft bubble
 * - Buddy's replies are plain, full-width text with a small avatar
 * - tool results / errors / confirmations appear as inline tinted chips
 */
class ChatAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int = when (getItem(position).role) {
        ChatRepository.ROLE_USER -> TYPE_USER
        ChatRepository.ROLE_ASSISTANT -> TYPE_ASSISTANT
        else -> TYPE_STATUS
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserHolder(ItemMsgUserBinding.inflate(inflater, parent, false))
            TYPE_ASSISTANT -> AssistantHolder(ItemMsgAssistantBinding.inflate(inflater, parent, false))
            else -> StatusHolder(ItemMsgStatusBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is UserHolder -> holder.bind(item)
            is AssistantHolder -> holder.bind(item)
            is StatusHolder -> holder.bind(item)
        }
    }

    class UserHolder(private val b: ItemMsgUserBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ChatMessage) {
            b.tvMessage.text = item.content
        }
    }

    class AssistantHolder(private val b: ItemMsgAssistantBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ChatMessage) {
            b.tvMessage.text = item.content
        }
    }

    class StatusHolder(private val b: ItemMsgStatusBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ChatMessage) {
            val ctx = b.root.context
            val background = when (item.role) {
                ChatRepository.ROLE_TOOL_RESULT -> R.color.bubble_tool_bg
                ChatRepository.ROLE_ERROR -> R.color.bubble_error_bg
                ChatRepository.ROLE_CONFIRMATION -> R.color.bubble_confirm_bg
                else -> R.color.buddy_surface_alt
            }
            val foreground = when (item.role) {
                ChatRepository.ROLE_TOOL_RESULT -> R.color.bubble_tool_text
                ChatRepository.ROLE_ERROR -> R.color.bubble_error_text
                ChatRepository.ROLE_CONFIRMATION -> R.color.bubble_confirm_text
                else -> R.color.status_muted
            }
            b.tvStatus.text = item.content
            b.tvStatus.setTextColor(ContextCompat.getColor(ctx, foreground))
            b.tvStatus.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(ctx, background))
        }
    }

    private object Diff : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem == newItem
    }

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
        private const val TYPE_STATUS = 2

        /** Used by the avatar in the drawer. */
        fun initialForUser(): String =
            SecureVault.getAccountGivenName()?.firstOrNull()?.uppercase() ?: "B"
    }
}
