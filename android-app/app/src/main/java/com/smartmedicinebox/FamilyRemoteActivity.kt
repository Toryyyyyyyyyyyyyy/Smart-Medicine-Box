/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.smartmedicinebox.data.api.ApiClient
import com.smartmedicinebox.data.repository.FamilyRemoteRepository
import com.smartmedicinebox.data.repository.SessionState
import com.smartmedicinebox.util.CryptoSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 家属远程界面：蓝色头部「<用户名>的智慧药箱」+ 仅药品/记录两个 tab（无设置）。
 * 复用 MedicineFragment / RecordFragment，通过 SessionState 路由到远程数据源。
 */
class FamilyRemoteActivity : BaseActivity() {

    private lateinit var medicineFragment: MedicineFragment
    private lateinit var recordFragment: RecordFragment
    private lateinit var trashFragment: FamilyTrashFragment

    /** 前台周期刷新任务：停留家属控制界面期间定期拉取，本人端生成/处理「未服用」按钮后及时同步显示 */
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionState.mode = SessionState.Mode.FAMILY_REMOTE
        setContentView(R.layout.activity_family_remote)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)

        if (savedInstanceState == null) {
            medicineFragment = MedicineFragment()
            recordFragment = RecordFragment()
            trashFragment = FamilyTrashFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, medicineFragment, TAG_MEDICINES)
                .add(R.id.fragmentContainer, recordFragment, TAG_RECORDS).hide(recordFragment)
                .add(R.id.fragmentContainer, trashFragment, TAG_TRASH).hide(trashFragment)
                .commit()
        } else {
            medicineFragment = supportFragmentManager.findFragmentByTag(TAG_MEDICINES) as MedicineFragment
            recordFragment = supportFragmentManager.findFragmentByTag(TAG_RECORDS) as RecordFragment
            trashFragment = supportFragmentManager.findFragmentByTag(TAG_TRASH) as FamilyTrashFragment
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_medicines -> showFragment(medicineFragment)
                R.id.nav_records -> showFragment(recordFragment)
                R.id.nav_trash -> showFragment(trashFragment)
            }
            true
        }

        // 只支持查看模式：仅显示药品与服药记录，隐藏回收站入口
        if (SessionState.viewOnly) {
            bottomNav.menu.findItem(R.id.nav_trash).isVisible = false
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时重新拉取控制对象最新数据，反映本人端/控制对象端的最新改动；
        // 失败时明确提示（如目标设备离线：控制对象未连接服务器），不静默保留旧数据
        if (FamilyRemoteRepository.isConnected) {
            lifecycleScope.launch {
                try {
                    FamilyRemoteRepository.fetchRemote()
                } catch (e: Exception) {
                    // 网络/证书/身份错误与目标离线（403）区分提示
                    Toast.makeText(this@FamilyRemoteActivity, ApiClient.friendlyMessage(e), Toast.LENGTH_SHORT).show()
                }
            }
            // 停留前台期间每 30 秒周期刷新（失败静默，下轮重试）：本人端闹钟后点击「暂不服用」等
            // 状态变化无需家属端切后台/重进即可看到对应按钮
            refreshJob?.cancel()
            refreshJob = lifecycleScope.launch {
                while (true) {
                    delay(30_000)
                    try {
                        FamilyRemoteRepository.fetchRemote()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
        refreshJob = null
    }

    override fun onDestroy() {
        if (isFinishing) {
            SessionState.mode = SessionState.Mode.OWN
            FamilyRemoteRepository.clear()
            // 结束家属会话后恢复本人登录密钥（若本机已登录），避免回到主界面被误判为"未解锁"而再次要求输入登录密码
            CryptoSession.restoreLoginKeys()
        }
        super.onDestroy()
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .hide(medicineFragment)
            .hide(recordFragment)
            .hide(trashFragment)
            .show(fragment)
            .commit()
    }

    companion object {
        private const val TAG_MEDICINES = "family_medicines"
        private const val TAG_RECORDS = "family_records"
        private const val TAG_TRASH = "family_trash"
    }
}
