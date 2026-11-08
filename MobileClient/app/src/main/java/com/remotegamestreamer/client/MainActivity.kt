package com.remotegamestreamer.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var address: EditText
    private lateinit var pin: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("connection", Context.MODE_PRIVATE)
        val padding = (24 * resources.displayMetrics.density).toInt()

        address = EditText(this).apply {
            hint = "PC address, e.g. 192.168.0.200"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("address", ""))
            isSingleLine = true
        }
        pin = EditText(this).apply {
            hint = "PIN shown in the host window"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSingleLine = true
        }
        val connect = Button(this).apply {
            text = "Connect"
            setOnClickListener {
                val host = address.text.toString().trim()
                val digits = pin.text.toString().trim()
                if (host.isEmpty() || digits.length != 6) {
                    Toast.makeText(this@MainActivity, "Enter the PC address and 6-digit PIN", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("address", host).apply()
                startActivity(
                    Intent(this@MainActivity, StreamActivity::class.java)
                        .putExtra(StreamActivity.EXTRA_HOST, host)
                        .putExtra(StreamActivity.EXTRA_PIN, digits.toInt()),
                )
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(padding, padding, padding, padding)
                addView(TextView(this@MainActivity).apply { text = "OmniDeck"; textSize = 22f; gravity = Gravity.CENTER })
                addView(address)
                addView(pin)
                addView(connect)
                addView(Button(this@MainActivity).apply {
                    text = "Settings"
                    setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
                })
            },
        )
    }
}
