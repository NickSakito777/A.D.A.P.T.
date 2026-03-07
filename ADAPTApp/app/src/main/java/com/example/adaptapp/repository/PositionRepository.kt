package com.example.adaptapp.repository

import android.content.Context
import com.example.adaptapp.model.ArmPosition
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// 位置持久化存储（SharedPreferences + JSON）
class PositionRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "adapt_positions"
        private const val KEY_POSITIONS = "saved_positions"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // 读取所有保存的位置
    fun getAll(): List<ArmPosition> {
        val json = prefs.getString(KEY_POSITIONS, null) ?: return emptyList()
        val type = object : TypeToken<List<ArmPosition>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 保存一个位置（同名覆盖）
    fun save(position: ArmPosition) {
        val positions = getAll().toMutableList()
        positions.removeAll { it.name == position.name }
        positions.add(position)
        writeAll(positions)
    }

    // 删除一个位置
    fun delete(name: String) {
        val positions = getAll().toMutableList()
        positions.removeAll { it.name == name }
        writeAll(positions)
    }

    // 重命名
    fun rename(oldName: String, newName: String) {
        val positions = getAll().toMutableList()
        val index = positions.indexOfFirst { it.name == oldName }
        if (index >= 0) {
            positions[index] = positions[index].copy(name = newName)
            writeAll(positions)
        }
    }

    // 写入全部位置
    private fun writeAll(positions: List<ArmPosition>) {
        prefs.edit().putString(KEY_POSITIONS, gson.toJson(positions)).apply()
    }
}
