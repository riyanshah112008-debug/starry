package com.example.buddy

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.buddy.data.entity.ChatMessage
import com.example.buddy.data.repo.ChatRepository
import com.example.buddy.databinding.ItemChatBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter that renders warm chat bubbles for:
 * - User messages (warm amber bubble, right-aligned)
 * - Buddy replies (cozy dark surface bubble, left-aligned)
 * - Thinking status -> "Hmm, let me think..." (soft gray bubble)
 * - Tool result -> "Okay, done: <result>" (green bubble)
 * - Error -> "Ah, that didn't work: <reason>" (soft red bubble)
 * - Confirmation -> "Heads up - this next bit is a big deal. Okay to go?"
 */
class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.ChatViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ChatViewHolder(
        private val binding: ItemChatBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

        fun bind(item: ChatMessage) {
            val context = binding.root.context
            binding.tvMessage.text = item.content
            binding.tvTime.text = timeFormat.format(Date(item.timestamp))

            val layoutParams = binding.cardBubble.layoutParams as FrameLayout.LayoutParams

            when (item.role) {
                ChatRepository.ROLE_USER -> {
                    layoutParams.gravity = Gravity.END
                    binding.cardBubble.setCardBackgroundColor(
                        ContextCompat.getColor(context, R.color.bubble_user_bg)
                    )
                    binding.tvMessage.setTextColor(
                        ContextCompat.getColor(context, R.color.bubble_user_text)
                    )
                    binding.tvTime.setTextColor(
                        ContextCompat.getColor(context, R.color.bubble_user_text)
                    )
                }

                ChatRepository.ROLE_THINKING -> {
                    layoutParams.gravity = Gravity.START
                    binding.cardBubble.setCardBackgroundColor(
                        ContextCompat.getColor(context, R.color.bubble_thinking_bg)
                    )
                    binding.tvMessage.setTextColor(
                        ContextCompat.getColor(context, R.color.bubble_thinking_text)
                    )
                    binding.tvTime.setTextColor(
                        ContextCompat.getColor(context, R.color.buddy_text_secondary)
                    )
                }

                ChatRepository.ROLE_TOOL_RESULT -> {
                    layoutParams.gravity = Gravity.START
                    binding.cardBubble.setCardBackgroundColor(
                        ContextCompat.getColor(context, R.color.bubble_tool_bg)
                    )
                    binding.tvMessage.setTextColor(
                        ContextCompat.getColor(context, R.color.bubble_tool_text)
                    )
                    binding.tvTime.setTextColor(
                        ContextCompat.getColor(context, R.color.bubble_tool_text)
                    )
                }

                ChatRepository.ROLE_ERROR -> {
                    layoutParams.gravity = Gravity.START
                    binding.cardBubble.setCardBackgroundColor(
                        ContextCompat.getColor(context, R.color.bubble_error_bg)
                    )
                    binding.tvMessage.setTextColor(
                        ContextCompat.getColor(context, R.color.bubble_error_text)
                    )
                    binding.tvTime.setTextColor(
                        ContextCompat.getColor(context, R.color.bubble_error_text)
                    )
                }

                ChatRepository.ROLE_CONFIRMATION -> {
                    layoutParams.gravity = Gravity.START
                    binding.cardBubble.setCardBackgroundColor(
                        ContextCompat.getColor(context, R.color.bubble_confirm_bg)
                    )
                    binding.tvMessage.setTextColor(
                        ContextCompat.getColor(context, R.color.bubble_confirm_text)
                    )
                    binding.tvTime.setTextColor(
                        ContextCompat.getColor(context, R.color.bubble_confirm_text)
                    )
                }

                else -> {
                    // Assistant warm conversational bubble
                    layoutParams.gravity = Gravity.START
                    binding.cardBubble.setCardBackgroundColor(
                        ContextCompat.getColor(context, R.color.bubble_buddy_bg)
                    )
                    binding.tvMessage.setTextColor(
                        ContextCompat.getColor(context, R.color.bubble_buddy_text)
                    )
                    binding.tvTime.setTextColor(
                        ContextCompat.getColor(context, R.color.buddy_text_secondary)
                    )
                }
            }

            binding.cardBubble.layoutParams = layoutParams
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}
