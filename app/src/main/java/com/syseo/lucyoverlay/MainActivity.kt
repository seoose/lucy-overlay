package com.syseo.lucyoverlay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.chip.Chip
import com.syseo.lucyoverlay.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        buildPetChips()
        binding.switchEdgeOnly.isChecked = PetOverlayService.edgeOnly
        binding.switchEdgeOnly.setOnCheckedChangeListener { _, checked ->
            if (PetOverlayService.running) {
                val intent = Intent(this, PetOverlayService::class.java).apply {
                    action = PetOverlayService.ACTION_UPDATE
                    putStringArrayListExtra(
                        PetOverlayService.EXTRA_PET_IDS,
                        ArrayList(PetOverlayService.activePetIds.ifEmpty { selectedPetIds() })
                    )
                    putExtra(PetOverlayService.EXTRA_EDGE_ONLY, checked)
                }
                ContextCompat.startForegroundService(this, intent)
            }
        }
        binding.btnPermission.setOnClickListener { requestOverlayPermission() }
        binding.btnBattery.setOnClickListener { requestBatteryOptimization() }
        binding.btnToggle.setOnClickListener { togglePets() }
        binding.btnApply.setOnClickListener { applyPetSelection() }

        // Defer permission prompt so first frame can render safely.
        binding.root.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    runCatching {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun applySystemBarInsets() {
        val rootPadLeft = binding.root.paddingLeft
        val rootPadRight = binding.root.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = rootPadLeft,
                top = bars.top,
                right = rootPadRight,
                bottom = 0
            )
            windowInsets
        }

        val barPadLeft = binding.bottomBar.paddingLeft
        val barPadTop = binding.bottomBar.paddingTop
        val barPadRight = binding.bottomBar.paddingRight
        val barPadBottom = binding.bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = barPadLeft,
                top = barPadTop,
                right = barPadRight,
                bottom = barPadBottom + bars.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun buildPetChips() {
        binding.petChips.removeAllViews()
        val active = PetOverlayService.activePetIds.toSet()
        val defaults = setOf("chani", "yeobi", "wonni", "iri")
        val iconPx = (40 * resources.displayMetrics.density).toInt()
        PetCatalog.all.forEach { pet ->
            val chip = Chip(this).apply {
                text = pet.label
                tag = pet.id
                isCheckable = true
                isCheckedIconVisible = true
                isChipIconVisible = true
                chipIconSize = iconPx.toFloat()
                chipIcon = ContextCompat.getDrawable(this@MainActivity, pet.thumbRes)
                iconStartPadding = 4f
                isChecked = if (active.isNotEmpty()) pet.id in active else pet.id in defaults
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            chip.setOnCheckedChangeListener { _, _ ->
                if (PetOverlayService.running) {
                    binding.btnApply.isEnabled = true
                    binding.btnApply.alpha = 1f
                }
            }
            binding.petChips.addView(chip)
        }
    }

    private fun refreshUi() {
        val canDraw = Settings.canDrawOverlays(this)
        binding.permissionStatus.text = if (canDraw) {
            getString(R.string.overlay_granted)
        } else {
            getString(R.string.overlay_permission_body)
        }
        binding.btnPermission.isEnabled = !canDraw
        binding.btnPermission.text = if (canDraw) {
            getString(R.string.overlay_granted)
        } else {
            getString(R.string.grant_overlay)
        }

        val batteryOk = isIgnoringBatteryOptimizations()
        binding.batteryStatus.text = if (batteryOk) {
            getString(R.string.battery_ok)
        } else {
            getString(R.string.battery_body)
        }
        binding.btnBattery.isEnabled = !batteryOk
        binding.btnBattery.text = if (batteryOk) {
            getString(R.string.battery_ok)
        } else {
            getString(R.string.battery_grant)
        }

        // Keep launch button always tappable — guides to overlay permission if needed.
        binding.btnToggle.isEnabled = true
        binding.btnToggle.alpha = 1f
        binding.btnToggle.text = if (PetOverlayService.running) {
            getString(R.string.stop_pets)
        } else {
            getString(R.string.start_pets)
        }
        val canApply = canDraw && PetOverlayService.running
        binding.btnApply.isEnabled = canApply
        binding.btnApply.alpha = if (canApply) 1f else 0.45f
    }

    private fun putCommonExtras(intent: Intent) {
        intent.putStringArrayListExtra(PetOverlayService.EXTRA_PET_IDS, selectedPetIds())
        intent.putExtra(PetOverlayService.EXTRA_EDGE_ONLY, binding.switchEdgeOnly.isChecked)
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimization() {
        if (isIgnoringBatteryOptimizations()) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun selectedPetIds(): ArrayList<String> {
        val ids = ArrayList<String>()
        for (i in 0 until binding.petChips.childCount) {
            val chip = binding.petChips.getChildAt(i) as? Chip ?: continue
            if (chip.isChecked) {
                (chip.tag as? String)?.let { ids += it }
            }
        }
        if (ids.isEmpty()) ids += "chani"
        return ids
    }

    private fun togglePets() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_body, Toast.LENGTH_SHORT).show()
            requestOverlayPermission()
            return
        }

        if (PetOverlayService.running) {
            // Must use startForegroundService-compatible delivery on some OEMs;
            // STOP handler removes FGS itself.
            val intent = Intent(this, PetOverlayService::class.java).apply {
                action = PetOverlayService.ACTION_STOP
            }
            try {
                startService(intent)
            } catch (_: Exception) {
                ContextCompat.startForegroundService(this, intent)
            }
            binding.btnToggle.isEnabled = false
            binding.root.postDelayed({ refreshUi() }, 400)
        } else {
            val intent = Intent(this, PetOverlayService::class.java).apply {
                action = PetOverlayService.ACTION_START
                putCommonExtras(this)
            }
            ContextCompat.startForegroundService(this, intent)
            binding.root.postDelayed({ refreshUi() }, 400)
        }
    }

    private fun applyPetSelection() {
        if (!PetOverlayService.running) {
            Toast.makeText(this, "먼저 펫을 출동시켜 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, PetOverlayService::class.java).apply {
            action = PetOverlayService.ACTION_UPDATE
            putCommonExtras(this)
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, R.string.apply_done, Toast.LENGTH_SHORT).show()
        binding.root.postDelayed({ refreshUi() }, 400)
    }
}
