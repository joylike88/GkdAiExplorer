package com.toolbox.smartcleaner.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.toolbox.smartcleaner.R
import com.toolbox.smartcleaner.ai.*
import com.toolbox.smartcleaner.engine.RuleEngine
import com.toolbox.smartcleaner.service.ObservationService
import com.toolbox.smartcleaner.service.AiProcessingService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * AI 发现页面 — 触发 AI 探索、显示进度和结果
 */
class DiscoverFragment : Fragment(R.layout.fragment_discover) {

    companion object {
        const val TAG = "DiscoverFragment"
    }

    private lateinit var btnStartScan: com.google.android.material.button.MaterialButton
    private lateinit var scanProgressContainer: View
    private lateinit var scanProgress: com.google.android.material.progressindicator.LinearProgressIndicator
    private lateinit var scanStatus: android.widget.TextView
    private lateinit var resultsContainer: View
    private lateinit var resultsRecycler: androidx.recyclerview.widget.RecyclerView
    private lateinit var emptyState: android.widget.TextView
    private lateinit var resultSummary: android.widget.TextView

    private var adapter: PatternAdapter? = null
    private val controller: AiExplorerController? by lazy {
        val service = ObservationService.instance ?: return@lazy null
        val ruleEngine = service.getRuleEngine()
        AiExplorerController(
            ruleEngine = ruleEngine,
            serviceProvider = { ObservationService.instance },
            llmClient = LlmClient()
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnStartScan = view.findViewById(R.id.btn_start_scan)
        scanProgressContainer = view.findViewById(R.id.scan_progress_container)
        scanProgress = view.findViewById(R.id.scan_progress)
        scanStatus = view.findViewById(R.id.scan_status)
        resultsContainer = view.findViewById(R.id.results_container)
        resultsRecycler = view.findViewById(R.id.results_recycler)
        emptyState = view.findViewById(R.id.empty_state)
        resultSummary = view.findViewById(R.id.result_summary)

        setupRecycler()
        setupClickListeners()

        // 监听 AI 控制器状态变化
        controller?.let { ctrl ->
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    ctrl.state.collectLatest { state ->
                        onStateChanged(state)
                    }
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    ctrl.lastResult.collectLatest { result ->
                        if (result != null) onResultReady(result)
                    }
                }
            }
        }
    }

    private fun setupRecycler() {
        resultsRecycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = PatternAdapter()
        resultsRecycler.adapter = adapter
    }

    private fun setupClickListeners() {
        btnStartScan.setOnClickListener { startScan() }
    }

    private fun startScan() {
        if (controller == null) {
            Snackbar.make(requireView(), R.string.toast_accessibility_required, Snackbar.LENGTH_SHORT).show()
            return
        }

        btnStartScan.isEnabled = false
        scanProgressContainer.visibility = View.VISIBLE
        resultsContainer.visibility = View.GONE
        emptyState.visibility = View.GONE
        scanStatus.text = getString(R.string.discover_scanning)
        scanProgress.progress = 0

        // 获取当前前台应用包名
        val pkg = controller.getCurrentForegroundApp()
        if (pkg == null) {
            Toast.makeText(requireContext(), R.string.toast_no_foreground_app, Toast.LENGTH_SHORT).show()
            btnStartScan.isEnabled = true
            scanProgressContainer.visibility = View.GONE
            return
        }

        // 通过后台服务触发 AI 探索
        val intent = android.content.Intent(requireContext(), AiProcessingService::class.java).apply {
            action = AiProcessingService.ACTION_EXPLORE
            putExtra(AiProcessingService.EXTRA_TARGET_PKG, pkg)
        }
        requireContext().startService(intent)
    }

    private fun onStateChanged(state: ExplorationState) {
        when (state) {
            ExplorationState.SCANNING -> {
                scanStatus.text = getString(R.string.discover_scanning)
                scanProgress.progress = 25
            }
            ExplorationState.ANALYZING -> {
                scanStatus.text = getString(R.string.discover_analyzing)
                scanProgress.progress = 50
            }
            ExplorationState.GENERATING_RULES -> {
                scanStatus.text = getString(R.string.discover_generating)
                scanProgress.progress = 75
            }
            ExplorationState.COMPLETED -> {
                scanStatus.text = getString(R.string.discover_completed)
                scanProgress.progress = 100
            }
            ExplorationState.ERROR -> {
                scanStatus.text = getString(R.string.discover_error)
                scanProgress.progress = 0
                btnStartScan.isEnabled = true
                scanProgressContainer.visibility = View.GONE
                Snackbar.make(requireView(), R.string.discover_error, Snackbar.LENGTH_LONG).show()
            }
            ExplorationState.IDLE -> {
                btnStartScan.isEnabled = true
                scanProgressContainer.visibility = View.GONE
            }
        }
    }

    private fun onResultReady(result: ExplorationResult) {
        btnStartScan.isEnabled = true
        scanProgressContainer.visibility = View.GONE

        resultSummary.text = getString(
            R.string.discover_pattern_found,
            result.discoveredPatterns.size,
            result.generatedRules.size
        )

        if (result.discoveredPatterns.isNotEmpty()) {
            resultsContainer.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            adapter?.submitList(result.discoveredPatterns)
            Snackbar.make(requireView(),
                getString(R.string.toast_scan_completed, result.generatedRules.size),
                Snackbar.LENGTH_LONG).show()
        } else {
            resultsContainer.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        }
    }

    // =========== Pattern 适配器 ===========

    private class PatternAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<PatternAdapter.ViewHolder>() {

        private var items = emptyList<DiscoveredPattern>()

        fun submitList(patterns: List<DiscoveredPattern>) {
            items = patterns
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val tvType: android.widget.TextView = view.findViewById(R.id.pattern_type)
            val tvDesc: android.widget.TextView = view.findViewById(R.id.pattern_desc)
            val tvConfidence: android.widget.TextView = view.findViewById(R.id.pattern_confidence)
            val tvSelector: android.widget.TextView = view.findViewById(R.id.pattern_selector)
            val btnApply: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btn_apply)
            val btnDiscard: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btn_discard)

            init {
                tvType = view.findViewById(R.id.pattern_type)
                tvDesc = view.findViewById(R.id.pattern_desc)
                tvConfidence = view.findViewById(R.id.pattern_confidence)
                tvSelector = view.findViewById(R.id.pattern_selector)
                btnApply = view.findViewById(R.id.btn_apply)
                btnDiscard = view.findViewById(R.id.btn_discard)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pattern, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val pattern = items[position]
            holder.tvType.text = getPatternTypeLabel(pattern.patternType)
            holder.tvDesc.text = pattern.description
            holder.tvConfidence.text = "置信度: ${(pattern.confidence * 100).toInt()}%"
            holder.tvSelector.text = "选择器: ${pattern.suggestedSelector}"

            holder.btnApply.setOnClickListener {
                // 启用规则
                val ctx = holder.itemView.context
                ctx.startService(
                    android.content.Intent(ctx, AiProcessingService::class.java).apply {
                        action = AiProcessingService.ACTION_APPLY_RULE
                        putExtra("rule_selector", pattern.suggestedSelector)
                        putExtra("rule_type", pattern.patternType.name)
                    }
                )
                Snackbar.make(holder.itemView, "规则已启用：${pattern.description}", Snackbar.LENGTH_SHORT).show()
            }

            holder.btnDiscard.setOnClickListener {
                Snackbar.make(holder.itemView, "已忽略", Snackbar.LENGTH_SHORT).show()
            }
        }

        override fun getItemCount() = items.size

        private fun getPatternTypeLabel(type: PatternType): String {
            return when (type) {
                PatternType.AD_CLOSE_BUTTON -> "广告关闭"
                PatternType.POPUP_DIALOG -> "弹窗"
                PatternType.SPLASH_AD -> "开屏广告"
                PatternType.BANNER_AD -> "横幅广告"
                PatternType.REWARD_VIDEO_SKIP -> "激励视频跳过"
                PatternType.PERMISSION_DIALOG -> "权限请求"
                PatternType.UPDATE_DIALOG -> "更新提示"
                PatternType.CUSTOM -> "自定义"
            }
        }
    }
}