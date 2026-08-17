package com.roxgarden

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnDebug: Button
    private lateinit var tvStatus: TextView
    private lateinit var rgMode: RadioGroup
    private lateinit var llPlants: LinearLayout
    private lateinit var projectionManager: MediaProjectionManager

    // id tanaman terurut prioritas (tinggi→rendah)
    private val selectedPlants = mutableListOf<String>()

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val PREFS_NAME = "roxgarden_prefs"
        const val KEY_MODE = "gardening_mode"
        var mediaProjectionIntent: Intent? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnDebug = findViewById(R.id.btnDebug)
        tvStatus = findViewById(R.id.tvStatus)
        rgMode = findViewById(R.id.rgMode)
        llPlants = findViewById(R.id.llPlants)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Restore pilihan tanaman + render checklist
        selectedPlants.clear()
        selectedPlants.addAll(PlantConfig.getSelectedIds(this))
        renderPlantList()

        // Restore mode dari SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        when (prefs.getString(KEY_MODE, "BOTH")) {
            "G1_ONLY" -> rgMode.check(R.id.rbG1)
            "G2_ONLY" -> rgMode.check(R.id.rbG2)
            else -> rgMode.check(R.id.rbBoth)
        }
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbG1 -> "G1_ONLY"
                R.id.rbG2 -> "G2_ONLY"
                else -> "BOTH"
            }
            prefs.edit().putString(KEY_MODE, mode).apply()
            tvStatus.text = "Mode: $mode"
        }

        // Kecualikan dari battery optimization — kurangi peluang sistem membunuh proses bot.
        requestBatteryExemption()

        // Tombol DIAGNOSA — satu-satunya alat debug di device tanpa adb (cloud phone).
        // Ditambah programatik supaya tak menyentuh layout XML.
        val btnDiag = Button(this).apply { text = "🔍 DIAGNOSA" }
        (btnDebug.parent as ViewGroup).let { p -> p.addView(btnDiag, p.indexOfChild(btnDebug) + 1) }

        // Toggle HUD status live (panel kiri-bawah + penanda tap)
        val btnHud = Button(this).apply { text = "👁 HUD STATUS" }
        (btnDiag.parent as ViewGroup).let { p -> p.addView(btnHud, p.indexOfChild(btnDiag) + 1) }
        btnHud.setOnClickListener {
            if (GardenService.instance == null) {
                tvStatus.text = "Status: Aktifkan Accessibility dulu"
            } else {
                startService(Intent(this, GardenService::class.java).apply { action = "HUD" })
            }
        }
        btnDiag.setOnClickListener {
            val svc = GardenService.instance
            if (svc == null) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Diagnosa")
                    .setMessage("Accessibility Service BELUM AKTIF.\n\nIni sebabnya bot tidak bergerak sama sekali. Nyalakan dulu:\nSettings → Accessibility → RoxGarden → ON")
                    .setPositiveButton("Buka Settings") { _, _ -> openAccessibilitySettings() }
                    .setNegativeButton("Tutup", null)
                    .show()
            } else {
                tvStatus.text = "Status: menjalankan diagnosa..."
                svc.runDiagnostics { report ->
                    runOnUiThread {
                        tvStatus.text = "Status: diagnosa selesai"
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Diagnosa")
                            .setMessage(report)
                            .setPositiveButton("Tutup", null)
                            .show()
                    }
                }
            }
        }

        btnStart.setOnClickListener {
            when {
                !isAccessibilityEnabled() -> {
                    tvStatus.text = "Status: Aktifkan Accessibility dulu"
                    openAccessibilitySettings()
                }
                mediaProjectionIntent == null -> {
                    tvStatus.text = "Status: Minta izin screen capture..."
                    startActivityForResult(
                        projectionManager.createScreenCaptureIntent(),
                        REQUEST_MEDIA_PROJECTION
                    )
                }
                else -> startGarden()
            }
        }

        btnStop.setOnClickListener {
            tvStatus.text = "Status: Stopped"
            val intent = Intent(this, GardenService::class.java)
            intent.action = "STOP"
            startService(intent)
        }

        btnDebug.setOnClickListener {
            val intent = Intent(this, GardenService::class.java)
            intent.action = "DEBUG"
            startService(intent)
            tvStatus.text = "Status: Debug overlay toggled"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            mediaProjectionIntent = data
            tvStatus.text = "Status: Izin diberikan, mulai..."
            startGarden()
        } else {
            tvStatus.text = "Status: Izin ditolak"
        }
    }

    // ── Checklist tanaman + reorder prioritas ──────────────────────────────
    private fun renderPlantList() {
        llPlants.removeAllViews()
        // urut tampil: terpilih (sesuai prioritas) dulu, lalu sisanya (urutan katalog)
        val ordered = selectedPlants.mapNotNull { PlantCatalog.byId(it) } +
            PlantCatalog.ALL.filter { it.id !in selectedPlants }

        for (plant in ordered) {
            val checked = plant.id in selectedPlants
            val prio = selectedPlants.indexOf(plant.id)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val cb = CheckBox(this).apply {
                text = if (checked) "${prio + 1}. ${plant.displayName}" else plant.displayName
                isChecked = checked
                setTextColor(0xFF222222.toInt())
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { togglePlant(plant.id) }
            }
            row.addView(cb)

            if (checked) {
                row.addView(smallBtn("▲") { movePlant(plant.id, -1) })
                row.addView(smallBtn("▼") { movePlant(plant.id, +1) })
            }
            llPlants.addView(row)
        }
    }

    private fun smallBtn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            minWidth = 0; minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }

    private fun togglePlant(id: String) {
        if (id in selectedPlants) selectedPlants.remove(id) else selectedPlants.add(id)
        persistAndRender()
    }

    private fun movePlant(id: String, delta: Int) {
        val i = selectedPlants.indexOf(id)
        val j = i + delta
        if (i < 0 || j < 0 || j >= selectedPlants.size) return
        selectedPlants[i] = selectedPlants[j].also { selectedPlants[j] = selectedPlants[i] }
        persistAndRender()
    }

    private fun persistAndRender() {
        PlantConfig.setSelectedIds(this, selectedPlants)
        renderPlantList()
    }

    private fun startGarden() {
        tvStatus.text = "Status: Running..."
        val intent = Intent(this, GardenService::class.java)
        intent.action = "START"
        intent.putExtra("projection_data", mediaProjectionIntent)
        startService(intent)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "${packageName}/${GardenService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(service)
    }

    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                ))
            }
        } catch (t: Throwable) {
            // Sebagian ROM/cloud phone tidak punya dialog ini — bukan masalah fatal.
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}