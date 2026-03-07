package com.example.adaptapp.controller

import com.example.adaptapp.connection.ConnectionManager
import com.example.adaptapp.connection.ConnectionState
import com.example.adaptapp.model.ArmPosition
import org.json.JSONObject

// 机械臂控制器 — 封装所有 T-code 命令
class ArmController(var connection: ConnectionManager) {

    // 移动到指定位置（T:102 最短路径 + T:700 Roll + T:703 Tilt）
    fun moveTo(position: ArmPosition, speed: Int = 0, acc: Int = 10) {
        // 臂关节同步移动
        send("""{"T":102,"base":${position.b},"shoulder":${position.s},"elbow":${position.e},"hand":${position.t},"spd":$speed,"acc":$acc}""")

        // Phone Roll（如果有值）
        position.p?.let { roll ->
            send("""{"T":700,"angle":$roll}""")
        }

        // Phone Tilt（如果有值）
        position.tilt?.let { tilt ->
            send("""{"T":703,"angle":$tilt}""")
        }
    }

    // 直接移动（T:120 无最短路径，用于安全折叠等需要确定路径的场景）
    fun moveDirectTo(position: ArmPosition, speed: Int = 600, acc: Int = 20) {
        send("""{"T":120,"base":${position.b},"shoulder":${position.s},"elbow":${position.e},"hand":${position.t},"spd":$speed,"acc":$acc}""")
    }

    // 急停
    fun emergencyStop() {
        send("""{"T":0}""")
    }

    // 读取当前关节反馈（T:105 → ESP32 返回 T:1051）
    fun readFeedback() {
        send("""{"T":105}""")
    }

    // 扭矩开关
    fun setTorque(enabled: Boolean) {
        val cmd = if (enabled) 1 else 0
        send("""{"T":210,"cmd":$cmd}""")
    }

    // Phone Roll 扭矩开关
    fun setRollTorque(enabled: Boolean) {
        val cmd = if (enabled) 1 else 0
        send("""{"T":702,"cmd":$cmd}""")
    }

    // Phone 横竖屏模式
    fun setPhoneMode(landscape: Boolean) {
        val cmd = if (landscape) 0 else 1
        send("""{"T":701,"cmd":$cmd}""")
    }

    // 安全折叠序列：先移到 torque closed 位置，再松扭矩
    fun foldAndRelease() {
        val torqueClosed = ArmPosition(
            name = "torque closed",
            b = 0.058, s = -0.060, e = 1.580, t = 3.137
        )
        moveDirectTo(torqueClosed)
        // 注意：实际使用时需要等机械臂到位后再松扭矩
        // 这里只发移动命令，松扭矩由 UI 层在确认到位后调用 setTorque(false)
    }

    // 解析 T:1051 反馈 JSON 为 ArmPosition
    companion object {
        fun parseFeedback(json: String): ArmPosition? {
            return try {
                val obj = JSONObject(json)
                if (obj.optInt("T") != 1051) return null
                ArmPosition(
                    name = "",
                    b = obj.getDouble("b"),
                    s = obj.getDouble("s"),
                    e = obj.getDouble("e"),
                    t = obj.getDouble("t"),
                    p = obj.optDouble("p").takeIf { !it.isNaN() },
                    tilt = obj.optDouble("tilt").takeIf { !it.isNaN() }
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun send(json: String) {
        if (connection.connectionState.value == ConnectionState.CONNECTED) {
            connection.send(json)
        }
    }
}
