package com.toolbox.smartcleaner.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.toolbox.smartcleaner.R
import com.toolbox.smartcleaner.ai.LlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页面 — LLM 配置、功能开关、版本信息
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val PREFS_NAME = "settings_prefs"
        private const val KEY_API_ENDPOINT = "api_endpoint"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_AUTO_SCAN = "auto_scan"
        private const val KEY_NOTIFICATIONS = "notifications"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupClickListeners()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun setupClickListeners() {
        binding.btnTestConnection.setOnClickListener {
            testConnection()
        }

        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }

        binding.btnPrivacy.setOnClickListener {
            Snackbar.make(binding.root, R.string.settings_privacy_summary, Snackbar.LENGTH_LONG).show()
        }

        binding.btnOpenSource.setOnClickListener {
            Snackbar.make(binding.root, R.string.settings_open_source_summary, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.etApiEndpoint.setText(prefs.getString(KEY_API_ENDPOINT, "https://api.moonshot.cn/v1"))
        binding.etApiKey.setText(prefs.getString(KEY_API_KEY, ""))
        binding.etModel.setText(prefs.getString(KEY_MODEL, "moonshot-v1-8k"))
        binding.switchAutoScan.isChecked = prefs.getBoolean(KEY_AUTO_SCAN, false)
        binding.switchNotifications.isChecked = prefs.getBoolean(KEY_NOTIFICATIONS, true)
    }

    private fun saveSettings() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_API_ENDPOINT, binding.etApiEndpoint.text?.toString()?.trim() ?: "")
            putString(KEY_API_KEY, binding.etApiKey.text?.toString()?.trim() ?: "")
            putString(KEY_MODEL, binding.etModel.text?.toString()?.trim() ?: "")
            putBoolean(KEY_AUTO_SCAN, binding.switchAutoScan.isChecked)
            putBoolean(KEY_NOTIFICATIONS, binding.switchNotifications.isChecked)
            apply()
        }
        Snackbar.make(binding.root, R.string.toast_settings_saved, Snackbar.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        val endpoint = binding.etApiEndpoint.text?.toString()?.trim() ?: ""
        val apiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
        val model = binding.etModel.text?.toString()?.trim() ?: ""

        if (endpoint.isBlank() || apiKey.isBlank()) {
            Snackbar.make(binding.root, R.string.settings_connection_missing_fields, Snackbar.LENGTH_SHORT).show()
            return
        }

        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = getString(R.string.loading)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val client = LlmClient(baseUrl = endpoint, apiKey = apiKey, model = model)
                    client.chatCompletion("说\"连接成功\"")
                    true
                } catch (e: Exception) {
                    Log.w("Settings", "Connection test failed", e)
                    false
                }
            }

            binding.btnTestConnection.isEnabled = true
            binding.btnTestConnection.text = getString(R.string.settings_test_connection)

            if (result) {
                Snackbar.make(binding.root, R.string.settings_connection_success, Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, R.string.settings_connection_failed_generic, Snackbar.LENGTH_LONG).show()
            }
        }
    }
}