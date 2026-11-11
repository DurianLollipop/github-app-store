package dev.githubstore.data

import android.content.Context

class AgreementStore(context: Context) {
    private val prefs = context.getSharedPreferences("user-agreement", Context.MODE_PRIVATE)
    val accepted get() = prefs.getString("version", null) == VERSION
    fun accept() { check(prefs.edit().putString("version", VERSION).putLong("acceptedAt", System.currentTimeMillis()).commit()) { "无法保存协议确认" } }
    companion object { const val VERSION = "2026-11-11.1" }
}
