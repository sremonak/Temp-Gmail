package com.tempgmail.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.tempgmail.app.api.SmsClient
import com.tempgmail.app.api.SmsNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Public temporary phone numbers (receive-sms-free.cc) — real numbers,
 * real shared inboxes, zero API keys.
 */
class NumbersActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var progress: View
    private lateinit var empty: TextView
    private lateinit var errorBlock: View
    private lateinit var errorText: TextView
    private lateinit var btnRetry: MaterialButton
    private lateinit var adapter: NumberAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_numbers)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        recycler = findViewById(R.id.recycler)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.empty)
        errorBlock = findViewById(R.id.errorBlock)
        errorText = findViewById(R.id.errorText)
        btnRetry = findViewById(R.id.btnRetry)

        adapter = NumberAdapter { number ->
            startActivity(
                Intent(this, NumberInboxActivity::class.java)
                    .putExtra(NumberInboxActivity.EXTRA_PREFIX, number.prefix)
                    .putExtra(NumberInboxActivity.EXTRA_PHONE, number.phone)
                    .putExtra(NumberInboxActivity.EXTRA_COUNTRY, number.country),
            )
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        btnRetry.setOnClickListener { load() }

        load()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        errorBlock.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { SmsClient.listNumbers() }
                adapter.submit(list)
                progress.visibility = View.GONE
                if (list.isEmpty()) {
                    empty.visibility = View.VISIBLE
                    empty.setText(R.string.numbers_empty)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("TempGmail", "numbers error: ${e.message}")
                progress.visibility = View.GONE
                errorBlock.visibility = View.VISIBLE
                errorText.setText(R.string.numbers_error)
            }
        }
    }

    private inner class NumberAdapter(
        private val onClick: (SmsNumber) -> Unit,
    ) : RecyclerView.Adapter<NumberAdapter.VH>() {

        private val items = mutableListOf<SmsNumber>()

        fun submit(list: List<SmsNumber>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val card: MaterialCardView = v.findViewById(R.id.card)
            val country: TextView = v.findViewById(R.id.numberCountry)
            val phone: TextView = v.findViewById(R.id.numberPhone)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_sms_number, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val n = items[position]
            holder.country.text = n.country
            holder.phone.text = n.phone
            holder.card.setOnClickListener { onClick(n) }
        }
    }
}
