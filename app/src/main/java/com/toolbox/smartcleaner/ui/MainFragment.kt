package com.toolbox.smartcleaner.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.toolbox.smartcleaner.R
import com.toolbox.smartcleaner.service.ObservationService
import com.toolbox.smartcleaner.engine.RuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页 — 显示服务状态、快速扫描入口、统计概览
 */
class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun setupClickListeners() {
        binding.btnQuickScan.setOnClickListener {
            val service = ObservationService.instance
            if (service == null) {
                showServiceDisabledDialog()
                return@setOnClickListener
            }
            binding.btnQuickScan.isEnabled = false
            binding.btnQuickScan.text = getString(R.string.scanning)
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val root = service.getFreshRoot()
                    val pkg = root?.packageName?.toString() ?: return@withContext
                    root?.recycle()
                    
                    // 触发 AI 探索
                    val aiService = Intent(requireContext(), AiProcessingService::class.java).apply {
                        action = AiProcessingService.ACTION_EXPLORE
                        putExtra(AiProcessingService.EXTRA_TARGET_PKG, pkg)
                    }
                    requireContext().startService(aiService)
                }
                refreshStatus()
            }
        }

        binding.btnViewRules.setOnClickListener {
            findNavController().navigate(R.id.action_main_to_rules)
        }

        binding.btnGoDiscover.setOnClickListener {
            findNavController().navigate(R.id.action_main_to_discover)
        }
    }

    private fun refreshStatus() {
        val service = ObservationService.instance
        val isRunning = service != null
        
        binding.tvServiceStatus.text = if (isRunning) {
            getString(R.string.service_running)
        } else {
            getString(R.string.service_not_running)
        }
        binding.tvServiceStatus.setTextColor(
            if (isRunning) requireContext().getColor(R.color.success_green)
            else requireContext().getColor(R.color.warning_orange)
        )
        
        binding.btnQuickScan.isEnabled = isRunning
        
        // 统计规则数量
        lifecycleScope.launch(Dispatchers.IO) {
            val rules = RuleEngine.instance?.getRulesByApp(null) ?: emptyList()
            requireActivity().runOnUiThread {
                binding.tvRuleCount.text = getString(R.string.rules_loaded_count, rules.size)
            }
        }
    }

    private fun showServiceDisabledDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.service_not_running)
            .setMessage(R.string.service_not_running_msg)
            .setPositiveButton(R.string.go_settings) { _, _ ->
                val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}