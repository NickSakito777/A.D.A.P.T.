package com.example.adaptapp.model

// 机械臂位置数据
// b/s/e/t: 臂关节弧度值 (rad)
// p/tilt: 手机支架角度值 (deg)，可选
data class ArmPosition(
    val name: String,
    val b: Double = 0.0,       // Base 底座旋转
    val s: Double = 0.0,       // Shoulder 肩部
    val e: Double = 1.57,      // Elbow 肘部
    val t: Double = 3.14,      // Hand 夹持器 (safe: 55deg-223deg)
    val p: Double? = null,     // Phone Roll (deg), 0-360
    val tilt: Double? = null   // Phone Tilt (deg), safe: 0-106 and 284-360
)
