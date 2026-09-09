package com.antitahrim.azad

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.antitahrim.azad.core.Report
import com.antitahrim.azad.databinding.ActivityMainBinding
import com.antitahrim.azad.vpn.VpnManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vpn by lazy { VpnManager.get(this) }

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startConnect()
        } else {
            Report.log("کاربر اجازه VPN را نداد")
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindSettings()
        showCrashIfAny()

        binding.actionButton.setOnClickListener {
            if (vpn.isConnected()) {
                lifecycleScope.launch { vpn.disconnect() }
            } else {
                requestPermissionThenConnect()
            }
        }

        binding.copyLogButton.setOnClickListener { copyReport() }

        binding.resetButton.setOnClickListener {
            lifecycleScope.launch {
                vpn.disconnect()
                vpn.prefs().clear()
                Report.clear()
                bindSettings()
                Toast.makeText(this@MainActivity, R.string.reset_done, Toast.LENGTH_LONG).show()
            }
        }

        observeState()
    }

    /**
     * اگر برنامه دفعه قبل کرش کرده، علتش را نشان می‌دهد.
     * بدون این، کرش فقط به صورت «برنامه بسته شد» دیده می‌شود.
     */
    private fun showCrashIfAny() {
        val crash = Report.lastCrash()
        if (crash.isNullOrBlank()) {
            binding.crashCard.visibility = View.GONE
            return
        }
        binding.crashCard.visibility = View.VISIBLE
        binding.crashText.text = crash.lines().take(6).joinToString("\n")
        binding.dismissCrashButton.setOnClickListener {
            Report.clearCrash()
            binding.crashCard.visibility = View.GONE
        }
    }

    private fun deviceHeader(): String =
        "اندروید " + Build.VERSION.RELEASE +
            " · " + Build.MANUFACTURER + " " + Build.MODEL +
            " · " + (Build.SUPPORTED_ABIS.firstOrNull() ?: "نامشخص")

    private fun copyReport() {
        val text = Report.asText(deviceHeader())
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("azad-report", text))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun bindSettings() {
        val store = vpn.prefs()
        binding.switchFragment.isChecked = store.fragmentTls
        binding.switchStrictDns.isChecked = store.strictIranDns
        binding.switchDomestic.isChecked = store.domesticDirect

        binding.switchFragment.setOnCheckedChangeListener { _, checked -> store.fragmentTls = checked }
        binding.switchStrictDns.setOnCheckedChangeListener { _, checked -> store.strictIranDns = checked }
        binding.switchDomestic.setOnCheckedChangeListener { _, checked -> store.domesticDirect = checked }
    }

    private fun requestPermissionThenConnect() {
        val intent: Intent? = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else startConnect()
    }

    private fun startConnect() {
        lifecycleScope.launch { vpn.connect() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vpn.state.collect { render(it) }
                }
                launch {
                    vpn.log.collect { lines ->
                        binding.logText.text = lines.takeLast(14).joinToString("\n")
                    }
                }
            }
        }
    }

    private fun render(state: VpnManager.State) = with(binding) {
        when (state) {
            is VpnManager.State.Disconnected -> {
                statusText.setText(R.string.state_disconnected)
                detailText.text = ""
                progress.visibility = View.GONE
                actionButton.setText(R.string.connect)
                actionButton.isEnabled = true
            }

            is VpnManager.State.Preparing -> {
                statusText.setText(R.string.state_preparing)
                detailText.text = ""
                progress.visibility = View.VISIBLE
                actionButton.isEnabled = false
            }

            is VpnManager.State.Registering -> {
                statusText.setText(R.string.state_registering)
                detailText.text = ""
                progress.visibility = View.VISIBLE
                actionButton.isEnabled = false
            }

            is VpnManager.State.Scanning -> {
                statusText.setText(R.string.state_scanning)
                detailText.text = state.tried.toString() + "/" + state.total + "  " + state.endpoint
                progress.visibility = View.VISIBLE
                actionButton.isEnabled = false
            }

            is VpnManager.State.Connected -> {
                statusText.setText(R.string.state_connected)
                detailText.text = state.endpoint
                progress.visibility = View.GONE
                actionButton.setText(R.string.disconnect)
                actionButton.isEnabled = true
            }

            is VpnManager.State.Failed -> {
                statusText.setText(R.string.state_failed)
                detailText.text = state.message
                progress.visibility = View.GONE
                actionButton.setText(R.string.connect)
                actionButton.isEnabled = true
            }
        }
    }
}
