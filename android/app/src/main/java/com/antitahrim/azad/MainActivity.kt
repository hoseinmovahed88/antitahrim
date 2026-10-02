package com.antitahrim.azad

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.antitahrim.azad.core.Report
import com.antitahrim.azad.databinding.ActivityMainBinding
import com.antitahrim.azad.vpn.AzadVpnService
import com.antitahrim.azad.vpn.VpnManager
import com.antitahrim.azad.warp.Store
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

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Report.log("کاربر اجازه اعلان را نداد")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindSettings()
        showCrashIfAny()

        // یک دکمه برای سه حالت: وصل کن، قطع کن، و لغو وسط جست‌وجو
        binding.actionButton.setOnClickListener {
            if (vpn.isBusyOrConnected()) {
                vpn.disconnectAsync()
            } else {
                requestPermissionThenConnect()
            }
        }

        askForNotificationsOnce()

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

    /**
     * سرنویس گزارش. نسخه برنامه اولین چیز است، چون وقتی گزارشی می‌رسد اولین
     * سؤال این است که از کدام ساخت آمده، و حدس زدنش وقت تلف کردن است.
     */
    private fun deviceHeader(): String =
        "آزاد " + BuildConfig.VERSION_NAME +
            " · اندروید " + Build.VERSION.RELEASE +
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
        binding.switchNotification.isChecked = store.persistentNotification

        // انتخاب حامل. تغییرش وسط اتصال اثری ندارد تا اتصال بعدی.
        when (store.transport) {
            Store.TRANSPORT_WARP -> binding.transportWarp.isChecked = true
            Store.TRANSPORT_BALE -> binding.transportBale.isChecked = true
            else -> binding.transportXray.isChecked = true
        }
        binding.baleLinkLayout.visibility =
            if (store.transport == Store.TRANSPORT_BALE) View.VISIBLE else View.GONE
        binding.transportGroup.setOnCheckedChangeListener { _, checkedId ->
            store.transport = when (checkedId) {
                R.id.transportWarp -> Store.TRANSPORT_WARP
                R.id.transportBale -> Store.TRANSPORT_BALE
                else -> Store.TRANSPORT_XRAY
            }
            binding.baleLinkLayout.visibility =
                if (checkedId == R.id.transportBale) View.VISIBLE else View.GONE
        }

        binding.baleLinkInput.setText(store.baleLink)
        binding.baleLinkInput.doAfterTextChanged { text -> store.baleLink = text?.toString().orEmpty() }

        binding.switchFragment.setOnCheckedChangeListener { _, checked -> store.fragmentTls = checked }
        binding.switchStrictDns.setOnCheckedChangeListener { _, checked -> store.strictIranDns = checked }
        binding.switchDomestic.setOnCheckedChangeListener { _, checked -> store.domesticDirect = checked }
        binding.switchNotification.setOnCheckedChangeListener { _, checked ->
            store.persistentNotification = checked
            // روی اتصال فعلی هم بلافاصله اثر می‌گذارد، نه از اتصال بعد
            AzadVpnService.applyNotificationSetting(this, checked)
        }
    }

    private fun requestPermissionThenConnect() {
        val intent: Intent? = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else startConnect()
    }

    /**
     * اتصال در دامنه خود برنامه اجرا می‌شود، نه در دامنه این صفحه. اگر کاربر
     * وسط جست‌وجو از برنامه بیرون برود، کار نیمه‌کاره لغو نمی‌شود.
     */
    private fun startConnect() {
        vpn.connectAsync()
    }

    /**
     * از اندروید ۱۳ نمایش اعلان اجازه می‌خواهد. بدون آن تونل کار می‌کند ولی
     * کلید قطع و وصل در نوار وضعیت دیده نمی‌شود. فقط یک بار پرسیده می‌شود.
     */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return

        val prefs = getSharedPreferences("azad_ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                actionButton.setText(R.string.cancel)
                actionButton.isEnabled = true
            }

            is VpnManager.State.Registering -> {
                statusText.setText(R.string.state_registering)
                detailText.text = ""
                progress.visibility = View.VISIBLE
                actionButton.setText(R.string.cancel)
                actionButton.isEnabled = true
            }

            is VpnManager.State.Scanning -> {
                statusText.setText(R.string.state_scanning)
                detailText.text = state.tried.toString() + "/" + state.total + "  " + state.endpoint
                progress.visibility = View.VISIBLE
                actionButton.setText(R.string.cancel)
                actionButton.isEnabled = true
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
