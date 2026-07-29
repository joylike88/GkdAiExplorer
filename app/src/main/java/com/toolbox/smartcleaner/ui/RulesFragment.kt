package com.toolbox.smartcleaner.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.toolbox.smartcleaner.R
import com.toolbox.smartcleaner.engine.CompiledRule
import com.toolbox.smartcleaner.engine.RuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 规则管理页面 — 显示当前应用的所有规则，支持启用/禁用/删除
 */
class RulesFragment : Fragment() {

    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<RulesFragmentArgs>()
    private val adapter = RulesAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupClickListeners()
        loadRules()
    }

    override fun onResume() {
        super.onResume()
        loadRules()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun setupRecyclerView() {
        binding.rulesList.layoutManager = LinearLayoutManager(requireContext())
        binding.rulesList.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.btnClearAll.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.rules_clear_all)
                .setMessage(R.string.rules_clear_all_confirm)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    RuleEngine.instance?.clearAllRules()
                    loadRules()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun loadRules() {
        lifecycleScope.launch(Dispatchers.IO) {
            val appId = args.appId
            val rules = if (appId.isNotBlank()) {
                RuleEngine.instance?.getRulesByApp(appId) ?: emptyList()
            } else {
                RuleEngine.instance?.getRulesByApp(null) ?: emptyList()
            }
            requireActivity().runOnUiThread {
                adapter.submitList(rules)
                binding.emptyView.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
                binding.rulesList.visibility = if (rules.isEmpty()) View.GONE else View.VISIBLE
                updateStats(rules)
            }
        }
    }

    private fun updateStats(rules: List<CompiledRule>) {
        val total = rules.size
        val active = rules.count { it.status == com.toolbox.smartcleaner.engine.RuleStatus.ACTIVE }
        val triggered = rules.sumOf { it.triggerCount }
        
        binding.statsTotal.text = total.toString()
        binding.statsActive.text = active.toString()
        binding.statsTriggered.text = triggered.toString()
    }
}

/**
 * 规则列表适配器
 */
class RulesAdapter : androidx.recyclerview.widget.ListAdapter<CompiledRule, RulesAdapter.RuleViewHolder>(
    androidx.recyclerview.widget.DiffUtil.ItemCallback<CompiledRule> { old, new ->
        old.id == new.id
    }
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
        val binding = ItemRuleBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false)
        return RuleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class RuleViewHolder(val binding: ItemRuleBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: CompiledRule) {
            binding.tvRuleName.text = rule.name
            binding.tvRuleApp.text = "应用: ${rule.appId}"
            binding.tvRuleAction.text = "动作: ${rule.actionType.name}"
            binding.tvTriggerCount.text = rule.triggerCount.toString()
            
            val lastTrigger = if (rule.lastTriggerTime > 0) {
                android.text.format.DateFormat.format("MM-dd HH:mm", rule.lastTriggerTime).toString()
            } else { "从未触发" }
            binding.tvLastTriggered.text = lastTrigger

            // 状态开关
            binding.switchRule.isChecked = rule.status == com.toolbox.smartcleaner.engine.RuleStatus.ACTIVE
            binding.switchRule.setOnCheckedChangeListener { _, isChecked ->
                rule.status = if (isChecked) com.toolbox.smartcleaner.engine.RuleStatus.ACTIVE else com.toolbox.smartcleaner.engine.RuleStatus.PENDING
                RuleEngine.instance?.injectRule(rule)
            }

            // 删除按钮
            binding.btnDelete.setOnClickListener {
                RuleEngine.instance?.removeRule(rule.appId, rule.id)
                // 列表会自动刷新，因为我们通过观察者模式或在父 Fragment 中重新加载
            }
        }
    }
}