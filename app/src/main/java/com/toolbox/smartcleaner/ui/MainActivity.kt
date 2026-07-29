package com.toolbox.smartcleaner.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.toolbox.smartcleaner.R
import com.toolbox.smartcleaner.service.ObservationService
import kotlinx.coroutines.launch

/**
 * 主界面 - 伪装为"智能工具箱"应用
 */
class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(setOf(
            R.id.nav_main,
            R.id.nav_discover,
            R.id.nav_rules,
            R.id.nav_settings
        ))

        setupActionBarWithNavController(navController, appBarConfiguration)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.setupWithNavController(navController)

        // 检查无障碍服务状态
        checkAccessibilityService()
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_scan -> {
                requestQuickScan()
                true
            }
            R.id.action_accessibility -> {
                openAccessibilitySettings()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun checkAccessibilityService() {
        val enabled = ObservationService.instance != null
        if (!enabled) {
            Toast.makeText(this, getString(R.string.service_not_running), Toast.LENGTH_LONG).show()
        }
    }

    private fun requestQuickScan() {
        Toast.makeText(this, getString(R.string.quick_scan_started), Toast.LENGTH_SHORT).show()
        // 触发 AI 探索
        lifecycleScope.launch {
            // 这里会通过 DiscoveryFragment 触发
            navController.navigate(R.id.nav_discover)
        }
    }

    private fun openAccessibilitySettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
}