#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RoArm-M2-S Position Manager / 位置管理工具
A command-line tool for saving and recalling arm positions.
用于保存和调用机械臂位置的命令行工具。
"""

import serial
import serial.tools.list_ports
import json
import time
import os
import math

# 配置 / Configuration
BAUD_RATE = 115200
POSITIONS_FILE = "saved_positions.json"
TIMEOUT = 2

# ID15 手部安全范围 / Hand (ID15) safe range: 55° ~ 223°
# 物理极限 ~50° 和 ~228°，各留5°余量
# 超出此范围会穿过0°/360°禁区，可能损坏3D打印件！
HAND_RAD_MIN = math.radians(55)   # 0.9599 rad
HAND_RAD_MAX = math.radians(223)  # 3.8921 rad

# ID17 Tilt 禁区 / Tilt (ID17) danger zone: 106° ~ 284°
# 物理极限 ~110° 和 ~280°，各留5°余量 + 1°确保安全区<180°
TILT_DANGER_MIN = 106
TILT_DANGER_MAX = 284

class RoArmController:
    def __init__(self):
        self.ser = None
        self.positions = {}
        self.load_positions()

    def list_ports(self):
        """列出所有可用串口 / List all available serial ports"""
        ports = serial.tools.list_ports.comports()
        print("\n可用串口 / Available ports:")
        print("-" * 40)
        for i, port in enumerate(ports):
            print(f"  [{i}] {port.device} - {port.description}")
        return ports

    def connect(self, port):
        """连接到串口 / Connect to serial port"""
        try:
            self.ser = serial.Serial(port, BAUD_RATE, timeout=TIMEOUT)
            time.sleep(2)  # 等待连接稳定 / Wait for connection to stabilize
            print(f"\n✅ 已连接 / Connected: {port}")
            return True
        except Exception as e:
            print(f"\n❌ 连接失败 / Connection failed: {e}")
            return False

    def send_command(self, cmd_dict):
        """发送JSON命令 / Send JSON command"""
        if not self.ser:
            print("❌ 未连接 / Not connected")
            return None

        cmd = json.dumps(cmd_dict) + "\n"
        self.ser.write(cmd.encode())
        print(f"📤 发送 / Sent: {cmd.strip()}")

        # 读取响应 / Read response
        time.sleep(0.5)
        response = ""
        while self.ser.in_waiting:
            response += self.ser.read(self.ser.in_waiting).decode('utf-8', errors='ignore')
            time.sleep(0.1)

        if response:
            print(f"📥 收到 / Received: {response.strip()}")
        return response

    def torque_off(self):
        """关闭扭矩 / Disable torque (allow manual movement)"""
        fold_pos = self.positions.get("torque closed")
        if fold_pos:
            # ⚠️ ID15 安全检查
            hand_val = fold_pos["t"]
            hand_blocked = hand_val < HAND_RAD_MIN or hand_val > HAND_RAD_MAX
            # ⚠️ ID17 Tilt 安全检查
            tilt_blocked = False
            if "tilt" in fold_pos:
                tilt_val = fold_pos["tilt"]
                tilt_blocked = TILT_DANGER_MIN < tilt_val < TILT_DANGER_MAX
            if hand_blocked:
                print(f"\n🚫 torque closed 的 hand={math.degrees(hand_val):.1f}° 超出安全范围!")
                print("   请重新保存 torque closed 位置")
                print("\n⚠️ 跳过折叠，直接关闭扭矩")
            elif tilt_blocked:
                print(f"\n🚫 torque closed 的 tilt={fold_pos['tilt']:.1f}° 在禁区内!")
                print("   请重新保存 torque closed 位置")
                print("\n⚠️ 跳过折叠，直接关闭扭矩")
            else:
                print("\n🔓 先移动到 torque closed，再关闭扭矩")
                print("   Move to torque closed, then torque OFF")
                # Step 1: Tilt moves FIRST (highest priority, avoid collision)
                if "tilt" in fold_pos:
                    self.send_command({"T": 703, "angle": float(fold_pos["tilt"]), "lock": False})
                    time.sleep(3)
                # Step 2: Arm folds + roll (已经是弧度，直接发送)
                cmd = {
                    "T": 120,
                    "base": fold_pos["b"],
                    "shoulder": fold_pos["s"],
                    "elbow": fold_pos["e"],
                    "hand": fold_pos["t"],
                    "spd": 0,
                    "acc": 10
                }
                self.send_command(cmd)
                if "p" in fold_pos:
                    self.send_command({"T": 700, "angle": float(fold_pos["p"]), "lock": False})
                time.sleep(5)
        else:
            print("\n⚠️ 未找到 torque closed，直接关闭扭矩")
            print("   torque closed not found, torque OFF directly")

        print("\n🔓 关闭扭矩 - 现在可以手动移动机械臂")
        print("   Torque OFF - You can now move the arm manually")
        self.send_command({"T": 210, "cmd": 0})

    def torque_on(self):
        """开启扭矩 / Enable torque (lock position)"""
        print("\n🔒 开启扭矩 - 机械臂锁定")
        print("   Torque ON - Arm is locked")
        self.send_command({"T": 210, "cmd": 1})

    def read_position(self):
        """读取当前位置 / Read current position"""
        print("\n📍 读取当前位置 / Reading current position...")
        response = self.send_command({"T": 105})

        if response:
            # 解析响应中的JSON / Parse JSON from response
            try:
                # 查找JSON部分 / Find JSON part
                start = response.find('{"T":1051')
                if start != -1:
                    end = response.find('}', start) + 1
                    json_str = response[start:end]
                    data = json.loads(json_str)

                    # ESP32 returns radians for b/s/e/t, degrees for p/tilt
                    position = {
                        "b": round(data["b"], 4),
                        "s": round(data["s"], 4),
                        "e": round(data["e"], 4),
                        "t": round(data["t"], 4)
                    }
                    if "p" in data:
                        position["p"] = round(data["p"], 2)
                    if "tilt" in data:
                        position["tilt"] = round(data["tilt"], 2)

                    # ⚠️ ID15 安全检查
                    hand_deg = math.degrees(position['t'])
                    hand_safe = HAND_RAD_MIN <= position['t'] <= HAND_RAD_MAX
                    hand_warn = "" if hand_safe else " ⚠️ 超出安全范围!"

                    # Display as degrees for readability
                    print("\n当前角度 / Current angles (degrees):")
                    print(f"  Base 底座:     {math.degrees(position['b']):.2f}°")
                    print(f"  Shoulder 肩部: {math.degrees(position['s']):.2f}°")
                    print(f"  Elbow 肘部:    {math.degrees(position['e']):.2f}°")
                    print(f"  Hand 夹持器:   {hand_deg:.2f}° [安全区 55°~223°]{hand_warn}")
                    if "p" in position:
                        print(f"  Phone Roll:    {position['p']}°")
                    if "tilt" in position:
                        print(f"  Phone Tilt:    {position['tilt']}°")

                    return position
            except json.JSONDecodeError as e:
                print(f"❌ JSON解析错误 / JSON parse error: {e}")

        return None

    def save_position(self, name):
        """保存当前位置 / Save current position"""
        position = self.read_position()
        if position:
            # ⚠️ ID15 安全检查：禁止保存超出安全范围的位置
            hand_val = position["t"]
            hand_deg = math.degrees(hand_val)
            if hand_val < HAND_RAD_MIN or hand_val > HAND_RAD_MAX:
                print(f"\n🚫 保存拒绝！Hand = {hand_deg:.1f}° 超出安全范围 [55°~223°]")
                print(f"   SAVE BLOCKED! Saving this would risk crossing the forbidden zone")
                print(f"   请先将 Hand 移动到安全范围内再保存")
                return
            # ⚠️ ID17 Tilt 安全检查：禁止保存禁区内的 Tilt 值
            if "tilt" in position:
                tilt_val = position["tilt"]
                if TILT_DANGER_MIN < tilt_val < TILT_DANGER_MAX:
                    print(f"\n🚫 保存拒绝！Tilt = {tilt_val:.1f}° 在禁区内 [{TILT_DANGER_MIN}°~{TILT_DANGER_MAX}°]")
                    print(f"   SAVE BLOCKED! Tilt is in danger zone, may damage 3D parts")
                    print(f"   请先将 Tilt 移出禁区再保存")
                    return
            self.positions[name] = position
            self.save_positions_to_file()
            print(f"\n✅ 位置已保存 / Position saved: '{name}'")
        else:
            print("\n❌ 无法保存 - 读取位置失败")
            print("   Cannot save - Failed to read position")

    def recall_position(self, name):
        """调用已保存的位置 / Recall a saved position"""
        if name not in self.positions:
            print(f"\n❌ 位置不存在 / Position not found: '{name}'")
            return

        pos = self.positions[name]
        print(f"\n🎯 移动到位置 / Moving to position: '{name}'")

        # ⚠️ ID15 安全检查：手部必须在 55°~223° 范围内
        hand_val = pos["t"]
        hand_deg = math.degrees(hand_val)
        if hand_val < HAND_RAD_MIN or hand_val > HAND_RAD_MAX:
            print(f"\n🚫 安全拦截！Hand = {hand_deg:.1f}° 超出安全范围 [55°~223°]")
            print(f"   SAFETY BLOCK! Hand value would cross the forbidden zone")
            print(f"   该位置数据可能已损坏，请删除后重新保存")
            print(f"   This position data may be corrupted, delete and re-save")
            return
        # ⚠️ ID17 Tilt 安全检查：禁止调用禁区内的 Tilt 值
        if "tilt" in pos:
            tilt_val = pos["tilt"]
            if TILT_DANGER_MIN < tilt_val < TILT_DANGER_MAX:
                print(f"\n🚫 安全拦截！Tilt = {tilt_val:.1f}° 在禁区内 [{TILT_DANGER_MIN}°~{TILT_DANGER_MAX}°]")
                print(f"   SAFETY BLOCK! Tilt is in danger zone, may damage 3D parts")
                print(f"   该位置数据可能已损坏，请删除后重新保存")
                return

        # 存的已经是弧度，直接发送
        cmd = {
            "T": 102,
            "base": pos["b"],
            "shoulder": pos["s"],
            "elbow": pos["e"],
            "hand": pos["t"],
            "spd": 0,
            "acc": 10
        }
        self.send_command(cmd)
        if "p" in pos:
            self.send_command({"T": 700, "angle": float(pos["p"])})
        if "tilt" in pos:
            self.send_command({"T": 703, "angle": float(pos["tilt"])})
        print("✅ 命令已发送 / Command sent")

    def list_positions(self):
        """列出所有保存的位置 / List all saved positions"""
        print("\n📋 已保存的位置 / Saved positions:")
        print("-" * 60)

        if not self.positions:
            print("  (空 / empty)")
            return

        for name, pos in self.positions.items():
            print(f"  📍 {name}")
            line = f"     b:{math.degrees(pos['b']):.1f}°, s:{math.degrees(pos['s']):.1f}°, e:{math.degrees(pos['e']):.1f}°, t:{math.degrees(pos['t']):.1f}°"
            if "p" in pos:
                line += f", p:{pos['p']:.1f}°"
            if "tilt" in pos:
                line += f", tilt:{pos['tilt']:.1f}°"
            print(line)

    def delete_position(self, name):
        """删除已保存的位置 / Delete a saved position"""
        if name in self.positions:
            del self.positions[name]
            self.save_positions_to_file()
            print(f"\n✅ 已删除 / Deleted: '{name}'")
        else:
            print(f"\n❌ 位置不存在 / Position not found: '{name}'")

    def load_positions(self):
        """从文件加载位置 / Load positions from file"""
        if os.path.exists(POSITIONS_FILE):
            try:
                with open(POSITIONS_FILE, 'r', encoding='utf-8') as f:
                    self.positions = json.load(f)
                print(f"📂 已加载 {len(self.positions)} 个位置 / Loaded {len(self.positions)} positions")
            except:
                self.positions = {}

    def save_positions_to_file(self):
        """保存位置到文件 / Save positions to file"""
        with open(POSITIONS_FILE, 'w', encoding='utf-8') as f:
            json.dump(self.positions, f, ensure_ascii=False, indent=2)

    def close(self):
        """关闭连接 / Close connection"""
        if self.ser:
            self.ser.close()
            print("\n👋 连接已关闭 / Connection closed")

    # --- Phone Holder Control Functions ---
    def phone_mode(self, mode):
        """设置手机支架模式 / Set phone holder mode"""
        self.send_command({"T": 701, "mode": mode})

    def phone_angle(self, angle):
        """设置手机支架角度 / Set phone holder angle"""
        self.send_command({"T": 700, "angle": float(angle)})

    def phone_torque(self, enable):
        """设置手机支架扭矩 / Set phone holder torque"""
        self.send_command({"T": 702, "cmd": 1 if enable else 0})

    # --- Phone Tilt Control Functions ---
    def phone_tilt_angle(self, angle):
        """设置手机俯仰角度 / Set phone tilt angle"""
        self.send_command({"T": 703, "angle": float(angle)})

    def phone_tilt_torque(self, enable):
        """设置手机俯仰扭矩 / Set phone tilt torque"""
        self.send_command({"T": 704, "cmd": 1 if enable else 0})

    def move_to_init(self):
        """回到开机初始状态 / Move to initial position (all joints at middle)"""
        print("\n🏠 回到初始状态 / Moving to initial position...")
        print("   所有关节将移动到中间位置 / All joints moving to middle position")
        self.send_command({"T": 100})
        print("✅ 命令已发送 / Command sent")

    def debug_mode(self):
        """调试模式 - 关闭所有电机扭矩，可自由读取位置 / Debug mode - all torque off"""
        print("\n" + "=" * 50)
        print("  🔧 调试模式 / Debug Mode")
        print("=" * 50)
        print("  关闭所有电机扭矩（包括手机支架）")
        print("  All motor torque OFF (including phone holder)")
        print()

        # 关闭主臂扭矩 (broadcast ID 254)
        self.send_command({"T": 210, "cmd": 0})
        time.sleep(0.2)
        # 关闭 Phone Roll 扭矩
        self.send_command({"T": 702, "cmd": 0})
        time.sleep(0.2)
        # 关闭 Phone Tilt 扭矩
        self.send_command({"T": 704, "cmd": 0})
        time.sleep(0.2)

        print("\n✅ 所有电机已释放 / All motors released")
        print("   现在可以手动移动机械臂和手机支架")
        print("   You can now freely move the arm and phone holder")

        # 调试循环
        while True:
            print("\n" + "-" * 40)
            print("  调试命令 / Debug Commands:")
            print("  [r] 📍 读取当前位置 / Read position")
            print("  [s] 💾 保存当前位置 / Save position")
            print("  [q] 🔙 退出调试模式 / Exit debug mode")
            print("-" * 40)

            cmd = input("调试 / Debug> ").strip().lower()

            if cmd == "r":
                self.read_position()
            elif cmd == "s":
                name = input("输入位置名称 / Enter position name: ").strip()
                if name:
                    self.save_position(name)
                else:
                    print("❌ 名称不能为空 / Name cannot be empty")
            elif cmd == "q":
                print("\n🔙 退出调试模式 / Exiting debug mode")
                print("   ⚠️  扭矩仍然关闭，请手动开启 [2]")
                print("   ⚠️  Torque is still OFF, use [2] to enable")
                break
            else:
                print("❌ 无效命令 / Invalid command")


def print_menu():
    """打印菜单 / Print menu"""
    print("\n" + "=" * 50)
    print("  RoArm-M2-S 位置管理工具 / Position Manager")
    print("=" * 50)
    print("  [1] 🔓 关闭扭矩 / Torque OFF (manual move)")
    print("  [2] 🔒 开启扭矩 / Torque ON (lock)")
    print("  [3] 📍 读取当前位置 / Read position")
    print("  [4] 💾 进入位置设定模式 / Position setup mode")
    print("  [5] 📋 查看已保存位置 / List positions")
    print("  [6] 🎯 调用已保存位置 / Recall position")
    print("  [7] 🗑️  删除位置 / Delete position")
    print("-" * 50)
    print("  📱 手机支架 Roll / Phone Roll Control")
    print("  [8]  📱 0° 竖屏 (Portrait)")
    print("  [9]  📱 90° 横屏 (Landscape)")
    print("  [10] 📱 180° 倒竖屏 (Inverted Portrait)")
    print("  [11] 📱 270° 倒横屏 (Inverted Landscape)")
    print("  [12] 🔓 Roll 解锁扭矩 (Unlock)")
    print("  [13] 🔒 Roll 锁定扭矩 (Lock)")
    print("  [14] 🎯 Roll 自定义角度 (Custom)")
    print("-" * 50)
    print("  📐 手机支架 Tilt / Phone Tilt Control")
    print("  [17] 🎯 Tilt 自定义角度 (Custom)")
    print("  [18] 🔓 Tilt 解锁扭矩 (Unlock)")
    print("  [19] 🔒 Tilt 锁定扭矩 (Lock)")
    print("-" * 50)
    print("  [15] 🏠 回到初始状态 / Reset to init position")
    print("  [16] 📤 发送自定义命令 / Send custom command")
    print("  [20] 🔧 调试模式 / Debug mode (all torque OFF)")
    print("  [0]  退出 / Exit")
    print("-" * 50)


def main():
    print("\n" + "=" * 50)
    print("  🦾 RoArm-M2-S 位置管理工具")
    print("     Position Manager Tool")
    print("=" * 50)

    controller = RoArmController()

    # 选择串口 / Select serial port
    ports = controller.list_ports()

    if not ports:
        print("\n❌ 没有找到串口 / No serial ports found")
        return

    print("\n请选择串口编号 / Select port number: ", end="")
    try:
        port_idx = int(input())
        port = ports[port_idx].device
    except (ValueError, IndexError):
        print("❌ 无效选择 / Invalid selection")
        return

    if not controller.connect(port):
        return

    # 主循环 / Main loop
    while True:
        print_menu()
        choice = input("请选择 / Choose: ").strip()

        if choice == "1":
            controller.torque_off()

        elif choice == "2":
            controller.torque_on()

        elif choice == "3":
            controller.read_position()

        elif choice == "4":
            # 新 Save Position 流程：拖动 → 确认 → 锁定 → 调 Roll → 保存
            # Step 1: 关闭扭矩，进入拖动模式
            controller.torque_off()
            print("\n👋 请拖动机械臂到目标位置")
            print("   Drag the arm to the desired position")
            while True:
                confirm = input("\n✅ 是否到达正确位置? / Reached position? (Y/N): ").strip().upper()
                if confirm == "Y":
                    break
                elif confirm == "N":
                    print("   继续调整... / Keep adjusting...")
                else:
                    print("   请输入 Y 或 N")

            # Step 2: 锁定所有电机
            print("\n🔒 锁定机械臂 / Locking arm...")
            controller.send_command({"T": 210, "cmd": 1})
            time.sleep(0.5)

            # Step 3: 单独关闭 ID16 Roll 扭矩（完全自由转动）
            controller.send_command({"T": 702, "cmd": 0})
            print("\n📱 Roll 已解锁 - 请手动调节手机横竖屏方向")
            print("   Roll unlocked - Adjust phone orientation manually")

            # Step 4: 输入名称并保存
            name = input("\n💾 输入位置名称 / Enter position name: ").strip()
            if name:
                controller.save_position(name)
            else:
                print("❌ 名称不能为空 / Name cannot be empty")

            # Step 5: 恢复 ID16 Roll 扭矩
            controller.send_command({"T": 702, "cmd": 1})
            print("🔒 Roll 已锁定 / Roll locked")

        elif choice == "5":
            controller.list_positions()

        elif choice == "6":
            controller.list_positions()
            name = input("\n输入要调用的位置名称 / Enter position name to recall: ").strip()
            if name:
                controller.recall_position(name)

        elif choice == "7":
            controller.list_positions()
            name = input("\n输入要删除的位置名称 / Enter position name to delete: ").strip()
            if name:
                controller.delete_position(name)

        # Phone Roll Controls
        elif choice == "8":
            controller.phone_mode("portrait")
            print("📱 已发送: 竖屏模式 (0°)")

        elif choice == "9":
            controller.phone_mode("landscape")
            print("📱 已发送: 横屏模式 (90°)")

        elif choice == "10":
            controller.phone_mode("portrait_inv")
            print("📱 已发送: 倒竖屏模式 (180°)")

        elif choice == "11":
            controller.phone_mode("landscape_inv")
            print("📱 已发送: 倒横屏模式 (270°)")

        elif choice == "12":
            controller.phone_torque(False)
            print("🔓 已发送: Roll 解锁扭矩")

        elif choice == "13":
            controller.phone_torque(True)
            print("🔒 已发送: Roll 锁定扭矩")

        elif choice == "14":
            try:
                angle = float(input("请输入 Roll 角度 (0-360): ").strip())
                controller.phone_angle(angle)
                print(f"🎯 已发送: Roll 转到 {angle}°")
            except ValueError:
                print("❌ 无效的角度数值")

        # Phone Tilt Controls
        elif choice == "17":
            try:
                angle = float(input("请输入 Tilt 角度 (0~106 或 284~360): ").strip())
                # Normalize to 0~360
                angle = angle % 360
                # Check danger zone (106~284)
                if 106 < angle < 284:
                    mid = (106 + 284) / 2  # 195
                    if angle <= mid:
                        angle = 106
                    else:
                        angle = 284
                    print(f"⚠️  角度在禁区内，已限制到 {angle}°")
                controller.phone_tilt_angle(angle)
                print(f"📐 已发送: Tilt 转到 {angle}°")
            except ValueError:
                print("❌ 无效的角度数值")

        elif choice == "18":
            controller.phone_tilt_torque(False)
            print("🔓 已发送: Tilt 解锁扭矩")

        elif choice == "19":
            controller.phone_tilt_torque(True)
            print("🔒 已发送: Tilt 锁定扭矩")

        elif choice == "15":
            controller.move_to_init()

        elif choice == "20":
            controller.debug_mode()

        elif choice == "16":
            cmd = input("输入JSON命令 / Enter JSON command: ").strip()
            try:
                cmd_dict = json.loads(cmd)
                controller.send_command(cmd_dict)
            except json.JSONDecodeError:
                print("❌ JSON格式错误 / Invalid JSON format")

        elif choice == "0":
            controller.close()
            print("\n👋 再见 / Goodbye!")
            break

        else:
            print("❌ 无效选择 / Invalid choice")


if __name__ == "__main__":
    main()
