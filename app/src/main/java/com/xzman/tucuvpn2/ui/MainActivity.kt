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

/**
 * Main Activity — fully compatible with Android TV remote navigation.
 *
 * Layout:
 *  - One large centered "Conectar" button
 *  - Scrollable log console below
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    /** VPN permission request launcher */
    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.connect()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButton()
        observeState()
        observeLogs()
    }

    // ─── UI Setup ──────────────────────────────────────────────────────────

    private fun setupButton() {
        binding.btnConnect.setOnClickListener {
            handleConnectClick()
        }

        // Ensure button responds to DPAD_CENTER and ENTER from TV remote
        binding.btnConnect.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                handleConnectClick()
                true
            } else {
                false
            }
        }

        // Give the button focus on start (TV navigation)
        binding.btnConnect.requestFocus()
    }

    private fun handleConnectClick() {
        val state = viewModel.vpnState.value

        if (state == VpnConnectionManager.State.CONNECTED ||
            state == VpnConnectionManager.State.CONNECTING ||
            state == VpnConnectionManager.State.FETCHING
        ) {
            viewModel.disconnect()
            return
        }

        // Check VPN permission
        val permissionIntent = viewModel.prepareVpnIntent()
        if (permissionIntent != null) {
            vpnPermissionLauncher.launch(permissionIntent)
        } else {
            viewModel.connect()
        }
    }

    // ─── State Observation ─────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.vpnState.collect { state ->
                    updateUiForState(state)
                }
            }
        }
    }

    private fun updateUiForState(state: VpnConnectionManager.State) {
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

    // ─── Log Observation ───────────────────────────────────────────────────

    private fun observeLogs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.logText.collect { text ->
                    binding.tvLog.text = text
                    // Auto-scroll to bottom
                    binding.scrollLog.post {
                        binding.scrollLog.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
    }
}
