package com.antitahrim.azad

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
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
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindSettings()

        binding.actionButton.setOnClickListener {
            if (vpn.isConnected()) {
                lifecycleScope.launch { vpn.disconnect() }
            } else {
                requestPermissionThenConnect()
            }
        }

        binding.resetButton.setOnClickListener {
            lifecycleScope.launch {
                vpn.disconnect()
                vpn.prefs().clear()
                bindSettings()
                Toast.makeText(this@MainActivity, R.string.reset_done, Toast.LENGTH_LONG).show()
            }
        }

        observeState()
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
                        binding.logText.text = lines.takeLast(12).joinToString("\n")
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
                progress.visibility = android.view.View.GONE
                actionButton.setText(R.string.connect)
                actionButton.isEnabled = true
            }

            is VpnManager.State.Registering -> {
                statusText.setText(R.string.state_registering)
                detailText.text = ""
                progress.visibility = android.view.View.VISIBLE
                actionButton.isEnabled = false
            }

            is VpnManager.State.Scanning -> {
                statusText.setText(R.string.state_scanning)
                detailText.text = "${state.tried}/${state.total}  ${state.endpoint}"
                progress.visibility = android.view.View.VISIBLE
                actionButton.isEnabled = false
            }

            is VpnManager.State.Connected -> {
                statusText.setText(R.string.state_connected)
                detailText.text = state.endpoint
                progress.visibility = android.view.View.GONE
                actionButton.setText(R.string.disconnect)
                actionButton.isEnabled = true
            }

            is VpnManager.State.Failed -> {
                statusText.setText(R.string.state_failed)
                detailText.text = state.message
                progress.visibility = android.view.View.GONE
                actionButton.setText(R.string.connect)
                actionButton.isEnabled = true
            }
        }
    }
}
