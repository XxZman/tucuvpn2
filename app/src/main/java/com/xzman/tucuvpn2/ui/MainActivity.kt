package com.xzman.tucuvpn2.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xzman.tucuvpn2.databinding.ActivityMainBinding
import com.xzman.tucuvpn2.vpn.VpnConnectionManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // ─── Activity result launchers ────────────────────────────────────────────

    /**
     * Handles the Android-level VPN permission dialog.
     * Shown once the first time any VPN app runs.
     */
    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) viewModel.connect()
            // If denied, do nothing — user can press the button again
        }

    /**
     * Handles authorization dialogs from ics-openvpn:
     *  • "Allow [TucuVPN2] to use OpenVPN for Android?" (first-run auth)
     *  • Android VPN permission if ics-openvpn itself needs it
     *
     * The VpnConnectionManager suspends its coroutine until this result
     * arrives, then resumes the connection flow.
     */
    private val ovpnAuthLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.onAuthorizationGranted()
            } else {
                viewModel.onAuthorizationDenied()
            }
        }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButton()
        observeState()
        observeLogs()
        observeAuthIntents()
    }

    // ─── Button ───────────────────────────────────────────────────────────────

    private fun setupButton() {
        binding.btnConnect.setOnClickListener { handleConnectClick() }

        binding.btnConnect.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                handleConnectClick(); true
            } else false
        }

        binding.btnConnect.requestFocus()
    }

    private fun handleConnectClick() {
        val state = viewModel.vpnState.value
        if (state == VpnConnectionManager.State.CONNECTED  ||
            state == VpnConnectionManager.State.CONNECTING ||
            state == VpnConnectionManager.State.FETCHING
        ) {
            viewModel.disconnect()
            return
        }

        // Check Android-level VPN permission first
        val permissionIntent = viewModel.prepareVpnIntent()
        if (permissionIntent != null) {
            vpnPermissionLauncher.launch(permissionIntent)
        } else {
            viewModel.connect()
        }
    }

    // ─── Observers ────────────────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.vpnState.collect { state ->
                    when (state) {
                        VpnConnectionManager.State.IDLE,
                        VpnConnectionManager.State.DISCONNECTED,
                        VpnConnectionManager.State.ERROR -> {
                            binding.btnConnect.text = "Conectar"
                            binding.btnConnect.isEnabled = true
                            binding.progressBar.visibility = View.GONE
                        }
                        VpnConnectionManager.State.FETCHING,
                        VpnConnectionManager.State.CONNECTING -> {
                            binding.btnConnect.text = "Cancelar"
                            binding.btnConnect.isEnabled = true
                            binding.progressBar.visibility = View.VISIBLE
                        }
                        VpnConnectionManager.State.CONNECTED -> {
                            binding.btnConnect.text = "Desconectar"
                            binding.btnConnect.isEnabled = true
                            binding.progressBar.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.logText.collect { text ->
                    binding.tvLog.text = text
                    binding.scrollLog.post {
                        binding.scrollLog.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
    }

    /**
     * Observes authorization intents emitted by VpnConnectionManager.
     * Launches the intent (showing the ics-openvpn authorization dialog)
     * and passes the result back so the manager can resume connecting.
     */
    private fun observeAuthIntents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.authIntentFlow.collect { intent ->
                    ovpnAuthLauncher.launch(intent)
                }
            }
        }
    }
}
