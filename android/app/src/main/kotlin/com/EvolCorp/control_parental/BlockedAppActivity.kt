package com.EvolCorp.control_parental

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import java.util.Locale

class BlockedAppActivity : Activity() {
    private var targetPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        setContentView(R.layout.activity_blocked_app)
        setFinishOnTouchOutside(false)

        targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val reason = intent.getStringExtra(EXTRA_REASON)
        val blockedPackageText = findViewById<TextView>(R.id.blockedPackageText)
        val reasonText = findViewById<TextView>(R.id.reasonText)
        val openDashboardButton = findViewById<Button>(R.id.openDashboardButton)
        val homeButton = findViewById<Button>(R.id.homeButton)

        blockedPackageText.text = targetPackage ?: "Aplicacion"
        reasonText.text = reasonToMessage(reason)

        ParentalPolicyStore.addEvent(
            context = this,
            type = EVENT_LOCK_SCREEN_SHOWN,
            packageName = targetPackage,
            reason = reason,
            details = "Pantalla de bloqueo mostrada"
        )

        openDashboardButton.setOnClickListener {
            openDashboard()
            finish()
        }

        homeButton.setOnClickListener {
            navigateHome()
            finish()
        }
    }

    override fun onBackPressed() {
        navigateHome()
        finish()
    }

    private fun navigateHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { startActivity(homeIntent) }
    }

    private fun openDashboard() {
        val dashboardIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        runCatching { startActivity(dashboardIntent) }
    }

    private fun reasonToMessage(reason: String?): String {
        return when (reason?.trim()?.lowercase(Locale.ROOT)) {
            ProtectionPolicyEngine.REASON_ALWAYS_BLOCKED -> "La pausaste manualmente para mantener el enfoque."
            ProtectionPolicyEngine.REASON_OUTSIDE_SCHEDULE -> "Esta app esta fuera del horario que definiste."
            ProtectionPolicyEngine.REASON_DAILY_LIMIT_REACHED -> "Ya alcanzaste el limite diario configurado."
            ProtectionPolicyEngine.REASON_SETTINGS_PROTECTED -> "Los ajustes estan protegidos mientras el modo enfoque esta activo."
            ProtectionPolicyEngine.REASON_UNINSTALL_PROTECTED -> "La desinstalacion esta protegida mientras el modo enfoque esta activo."
            else -> "Esta app esta suspendida por tus reglas de autocontrol."
        }
    }

    companion object {
        private const val EXTRA_PACKAGE_NAME = "extra_package_name"
        private const val EXTRA_REASON = "extra_reason"
        private const val EVENT_LOCK_SCREEN_SHOWN = "lock_screen_shown"

        fun launch(context: Context, packageName: String, reason: String?) {
            val intent = Intent(context, BlockedAppActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_REASON, reason)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}
