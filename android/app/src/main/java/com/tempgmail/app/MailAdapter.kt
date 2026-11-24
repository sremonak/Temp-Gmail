package com.tempgmail.app

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tempgmail.app.api.Avatars
import com.tempgmail.app.api.Dates
import com.tempgmail.app.model.MailMessage

class MailAdapter(
    private val onOpen: (MailMessage) -> Unit,
    private val onStar: (MailMessage) -> Unit,
) : RecyclerView.Adapter<MailAdapter.VH>() {

    private val items = mutableListOf<MailMessage>()

    /** Injected by the screen so the adapter stays a dumb view. */
    var isRead: (String) -> Boolean = { false }
    var isStarred: (String) -> Boolean = { false }

    fun submit(list: List<MailMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun current(): List<MailMessage> = items

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mail, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dot: View = itemView.findViewById(R.id.unreadDot)
        private val avatar: TextView = itemView.findViewById(R.id.avatar)
        private val sender: TextView = itemView.findViewById(R.id.sender)
        private val time: TextView = itemView.findViewById(R.id.time)
        private val subject: TextView = itemView.findViewById(R.id.subject)
        private val snippet: TextView = itemView.findViewById(R.id.snippet)
        private val star: ImageButton = itemView.findViewById(R.id.btnStar)

        fun bind(m: MailMessage) {
            val ctx = itemView.context
            val read = isRead(m.id)
            val starred = isStarred(m.id)

            itemView.setBackgroundColor(
                ContextCompat.getColor(
                    ctx,
                    if (read) R.color.colorSurface else R.color.unreadRowBg,
                ),
            )

            dot.visibility = if (read) View.GONE else View.VISIBLE
            sender.text = m.from
            sender.typeface = if (read) Typeface.DEFAULT else Typeface.DEFAULT_BOLD

            time.text = if (m.dateMillis > 0) Dates.relTime(m.dateMillis) else ""

            subject.text = m.subject.ifEmpty { ctx.getString(R.string.reader_subject_default) }

            if (m.snippet.isBlank()) {
                snippet.visibility = View.GONE
            } else {
                snippet.visibility = View.VISIBLE
                snippet.text = m.snippet
            }

            avatar.text = Avatars.initial(m.from)
            val bg = avatar.background.mutate()
            if (bg is GradientDrawable) {
                bg.setColor(Avatars.color(m.from + m.to + m.subject))
            }

            star.setImageResource(if (starred) R.drawable.ic_star_filled else R.drawable.ic_star)
            star.setColorFilter(
                ContextCompat.getColor(
                    ctx,
                    if (starred) R.color.star_gold else R.color.colorTextTertiary,
                ),
            )

            itemView.setOnClickListener { onOpen(m) }
            star.setOnClickListener { onStar(m) }
        }
    }
}
