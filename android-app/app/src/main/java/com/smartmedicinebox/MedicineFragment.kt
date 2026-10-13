/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.smartmedicinebox.data.api.ApiClient
import com.smartmedicinebox.data.db.MedicineEntity
import com.smartmedicinebox.data.db.RecordEntity
import com.smartmedicinebox.data.repository.DosePlanner
import com.smartmedicinebox.data.repository.FamilyRemoteRepository
import com.smartmedicinebox.data.repository.MedicineRepository
import com.smartmedicinebox.data.repository.SessionState
import com.smartmedicinebox.util.LocaleHelper
import com.smartmedicinebox.util.Prefs
import com.smartmedicinebox.util.SingleFlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * 药品 tab：今日用药概览 + 添加按钮
 */
class MedicineFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: MedicineAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_medicine, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerView)
        emptyView = view.findViewById(R.id.emptyView)

        adapter = MedicineAdapter(
            onItemClick = { medicine -> if (!SessionState.viewOnly) openEdit(medicine) },
            onTake = { medicine -> takeNow(medicine) },
            onTakePending = { dose -> takePending(dose) },
            viewOnly = { SessionState.viewOnly },
            zoneProvider = { SessionState.doseZone() }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        val fab = view.findViewById<FloatingActionButton>(R.id.fabAdd)
        if (SessionState.viewOnly) {
            fab.visibility = View.GONE
        } else {
            fab.setOnClickListener {
                startActivity(Intent(requireContext(), MedicineEditActivity::class.java))
            }
        }

        updateDateGreeting(view)
        observeMedicines()
        setupPullRefresh(view)
    }

    /**
     * 主页下拉刷新：仅「本人已开启家属控制」或「家属远程控制」模式下启用；
     * 一次刷新通过共享数据源同步更新主页、回收站、服药记录三个页面的数据。
     * 启用状态与实时模式绑定：页面恢复/重新显示时重新评估，关闭家人控制后立即禁用。
     */
    private var swipeRefresh: SwipeRefreshLayout? = null

    /** 是否允许下拉刷新：家属远程模式，或本人已开启家属控制 */
    private fun pullRefreshAllowed(): Boolean = SessionState.isFamilyRemote || Prefs.familyControlEnabled

    /** 将组件启用状态同步到当前模式（开关变化后回到页面立即生效，无需退出重进） */
    private fun updatePullRefreshEnabled() {
        swipeRefresh?.isEnabled = pullRefreshAllowed()
    }

    private fun setupPullRefresh(view: View) {
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        val swipe = swipeRefresh ?: return
        swipe.setOnRefreshListener {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // 入口校验：关闭家人控制/退出远程会话后即使组件状态更新延迟也不触发
                    if (!pullRefreshAllowed()) {
                        swipe.isRefreshing = false
                        return@launch
                    }
                    // SingleFlight 防止连续下拉产生重叠的刷新任务
                    SingleFlight.run("pull-refresh") {
                        if (SessionState.isFamilyRemote) {
                            // 手动刷新必须真正拉取（等待写操作完成，不静默跳过），失败抛出以便提示「刷新失败」
                            FamilyRemoteRepository.fetchRemoteForRefresh()
                        } else {
                            MedicineRepository.syncAll()
                        }
                    }?.let {
                        Toast.makeText(requireContext(), getString(R.string.refresh_success), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    // 目标离线（403 等）展示服务器具体原因；网络/证书/身份错误分类提示
                    val msg = if (e is java.io.IOException) ApiClient.friendlyMessage(e) else e.message
                    Toast.makeText(requireContext(), msg ?: getString(R.string.refresh_failed), Toast.LENGTH_SHORT).show()
                } finally {
                    swipe.isRefreshing = false
                }
            }
        }
        updatePullRefreshEnabled()
    }

    override fun onResume() {
        super.onResume()
        // 重新可见时刷新问候语/日期，跨时段返回也能更新
        updateDateGreeting(requireView())
        // 回到主页时按当前模式重新评估下拉刷新启用状态（如刚在账号设置中开关了家人控制）
        updatePullRefreshEnabled()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 从其他标签切回主页时同步启用状态
        if (!hidden) updatePullRefreshEnabled()
    }

    private fun observeMedicines() {
        viewLifecycleOwner.lifecycleScope.launch {
            // ~15s 心跳：跨 0 点 / 门控解锁时刷新按钮可用状态（viewLifecycleOwner 销毁时自动取消）
            val ticker = flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    delay(15_000)
                }
            }
            combine(
                SessionState.activeMedicines(),
                SessionState.pendingDoses(),
                SessionState.allRecords(),
                SingleFlight.inflight,
                ticker
            ) { medicines, doses, records, inflight, now ->
                // 家属远程模式按目标设备当前时间读数与目标用户本地时区，本人模式按本机时间
                val effectiveNow = if (SessionState.isFamilyRemote) FamilyRemoteRepository.seniorNow() else now
                val start = DosePlanner.todayStart(effectiveNow, SessionState.doseZone())
                medicines.map { medicine ->
                    val buttons = doses.filter { it.medicineId == medicine.id && it.scheduledTime >= start }
                    val enabled = DosePlanner.nextTarget(
                        medicine, records.filter { it.medicineId == medicine.id }, effectiveNow, SessionState.doseZone()
                    ) != null && "take:${medicine.id}" !in inflight
                    MedicineAdapter.MedicineRow(medicine, buttons, enabled)
                }
            }.collectLatest { rows ->
                adapter.submitList(rows)
                emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                recyclerView.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    /** 点击「服用」：有未服用则补最早一条，否则正常服用并推进闹钟 */
    private fun takeNow(medicine: MedicineEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                SingleFlight.run("take:${medicine.id}") { SessionState.takeNextDose(medicine) }
                    ?: return@launch
                Toast.makeText(requireContext(), getString(R.string.toast_taken), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 点击某条「xx:xx 未服用」按钮：仅补服该条 */
    private fun takePending(dose: RecordEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                SingleFlight.run("pending:${dose.id}") { SessionState.takePendingDose(dose) }
                    ?: return@launch
                Toast.makeText(requireContext(), getString(R.string.toast_taken), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateDateGreeting(view: View) {
        val tvDate = view.findViewById<TextView>(R.id.tvDate)
        val tvGreeting = view.findViewById<TextView>(R.id.tvGreeting)

        // 家属远程模式：蓝色头部显示「<用户名>的智慧药箱」替代问候语
        if (SessionState.isFamilyRemote) {
            tvDate.visibility = View.GONE
            tvGreeting.text = getString(R.string.family_remote_header, FamilyRemoteRepository.username ?: "")
            return
        }

        tvDate.visibility = View.VISIBLE
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour in 5..11 -> getString(R.string.greeting_morning)
            hour in 12..17 -> getString(R.string.greeting_afternoon)
            else -> getString(R.string.greeting_evening)
        }
        val dateStr = SimpleDateFormat(getString(R.string.date_pattern), LocaleHelper.currentLocale()).format(Date())
        tvDate.text = dateStr
        tvGreeting.text = getString(R.string.greeting_suffix, greeting)
    }

    private fun openEdit(medicine: MedicineEntity) {
        val intent = Intent(requireContext(), MedicineEditActivity::class.java)
        intent.putExtra("medicine_id", medicine.id)
        startActivity(intent)
    }
}
