package com.tempgmail.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.tempgmail.app.api.SmsClient
import com.tempgmail.app.api.SmsInboxItem
import com.tempgmail.app.api.SmsNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live inbox for one public number. Polls every 5s while visible;
 * OTP codes are highlighted and tap-to-copy.
 */
class NumberInboxActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PREFIX = "prefix"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_COUNTRY = "country"
    }

    private lateinit var recycler: RecyclerView
    private lateinit var progress: View
    private lateinit var empty: TextView
    private lateinit var errorBlock: View
    private lateinit var errorText: TextView
    private lateinit var btnRetry: MaterialButton
    private lateinit var adapter: SmsAdapter
    private lateinit var number: SmsNumber
    private var pollJob: Job? = null
    private val seen = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_number_inbox)

        number = SmsNumber(
            country = intent.getStringExtra(EXTRA_COUNTRY) ?: "",
            phone = intent.getStringExtra(EXTRA_PHONE) ?: "",
            prefix = intent.getStringExtra(EXTRA_PREFIX) ?: "",
        )

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = number.country.ifEmpty { getString(R.string.numbers_title) }
        toolbar.subtitle = number.phone
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_copy_number -> {
                    copy(number.phone, getString(R.string.toast_number_copied))
                    true
                }
                else -> false
            }
        }

        recycler = findViewById(R.id.recycler)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.empty)
        errorBlock = findViewById(R.id.errorBlock)
        errorText = findViewById(R.id.errorText)
        btnRetry = findViewById(R.id.btnRetry)

        adapter = SmsAdapter { otp ->
            copy(otp, getString(R.string.toast_otp_copied))
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        btnRetry.setOnClickListener { load(initial = true) }

        load(initial = true)
    }

    override fun onStart() {
        super.onStart()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                load(initial = false)
            }
        }
    }

    override fun onStop() {
        pollJob?.cancel()
        pollJob = null
        super.onStop()
    }

    private fun load(initial: Boolean) {
        if (initial) {
            progress.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
            errorBlock.visibility = View.GONE
        }
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { SmsClient.listMessages(number) }
                val fresh = list.filter { (it.sender + "|" + it.text) !in seen }
                fresh.forEach { seen.add(it.sender + "|" + it.text) }
                if (fresh.isNotEmpty()) {
                    adapter.prepend(fresh)
                }
                progress.visibility = View.GONE
                empty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                errorBlock.visibility = View.GONE
                if (adapter.itemCount == 0) empty.setText(R.string.inbox_empty)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("TempGmail", "sms inbox error: ${e.message}")
                progress.visibility = View.GONE
                if (adapter.itemCount == 0) {
                    errorBlock.visibility = View.VISIBLE
                    errorText.setText(R.string.inbox_error)
                }
            }
        }
    }

    private fun copy(text: String, toast: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("tempgmail", text))
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }

    private inner class SmsAdapter(
        private val onOtpClick: (String) -> Unit,
    ) : RecyclerView.Adapter<SmsAdapter.VH>() {

        private val items = mutableListOf<SmsInboxItem>()

        fun prepend(newItems: List<SmsInboxItem>) {
            items.addAll(0, newItems)
            notifyItemRangeInserted(0, newItems.size)
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val card: MaterialCardView = v.findViewById(R.id.card)
            val sender: TextView = v.findViewById(R.id.smsSender)
            val time: TextView = v.findViewById(R.id.smsTime)
            val text: TextView = v.findViewById(R.id.smsText)
            val otpChip: TextView = v.findViewById(R.id.otpChip)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_sms_message, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = items[position]
            holder.sender.text = m.sender
            holder.time.text = m.time
            holder.text.text = m.text
            if (m.otp != null) {
                holder.otpChip.visibility = View.VISIBLE
                holder.otpChip.text = getString(R.string.otp_chip, m.otp)
                holder.otpChip.setOnClickListener { onOtpClick(m.otp) }
            } else {
                holder.otpChip.visibility = View.GONE
                holder.otpChip.setOnClickListener(null)
            }
        }
    }
}
