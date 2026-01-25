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

# 配置 / Configuration
BAUD_RATE = 115200
POSITIONS_FILE = "saved_positions.json"
TIMEOUT = 2

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
                    
                    position = {
                        "b": round(data["b"], 4),
                        "s": round(data["s"], 4),
                        "e": round(data["e"], 4),
                        "t": round(data["t"], 4)
                    }
                    if "p" in data:
                        position["p"] = round(data["p"], 2)
                    
                    print("\n当前角度 / Current angles (radians):")
                    print(f"  Base 底座:     {position['b']}")
                    print(f"  Shoulder 肩部: {position['s']}")
                    print(f"  Elbow 肘部:    {position['e']}")
                    print(f"  Hand 夹持器:   {position['t']}")
                    if "p" in position:
                        print(f"  Phone 支架:    {position['p']}°")
                    
                    return position
            except json.JSONDecodeError as e:
                print(f"❌ JSON解析错误 / JSON parse error: {e}")
        
        return None
    
    def save_position(self, name):
        """保存当前位置 / Save current position"""
        position = self.read_position()
        if position:
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
        print("✅ 命令已发送 / Command sent")
    
    def list_positions(self):
        """列出所有保存的位置 / List all saved positions"""
        print("\n📋 已保存的位置 / Saved positions:")
        print("-" * 50)
        
        if not self.positions:
            print("  (空 / empty)")
            return
        
        for name, pos in self.positions.items():
            print(f"  📍 {name}")
            line = f"     b:{pos['b']:.3f}, s:{pos['s']:.3f}, e:{pos['e']:.3f}, t:{pos['t']:.3f}"
            if "p" in pos:
                line += f", p:{pos['p']:.1f}°"
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

    def move_to_init(self):
        """回到开机初始状态 / Move to initial position (all joints at middle)"""
        print("\n🏠 回到初始状态 / Moving to initial position...")
        print("   所有关节将移动到中间位置 / All joints moving to middle position")
        self.send_command({"T": 100})
        print("✅ 命令已发送 / Command sent")


def print_menu():
    """打印菜单 / Print menu"""
    print("\n" + "=" * 50)
    print("  RoArm-M2-S 位置管理工具 / Position Manager")
    print("=" * 50)
    print("  [1] 🔓 关闭扭矩 / Torque OFF (manual move)")
    print("  [2] 🔒 开启扭矩 / Torque ON (lock)")
    print("  [3] 📍 读取当前位置 / Read position")
    print("  [4] 💾 保存当前位置 / Save position")
    print("  [5] 📋 查看已保存位置 / List positions")
    print("  [6] 🎯 调用已保存位置 / Recall position")
    print("  [7] 🗑️  删除位置 / Delete position")
    print("-" * 50)
    print("  📱 手机支架控制 / Phone Holder Control")
    print("  [8]  📱 0° 竖屏 (Portrait)")
    print("  [9]  📱 90° 横屏 (Landscape)")
    print("  [10] 📱 180° 倒竖屏 (Inverted Portrait)")
    print("  [11] 📱 270° 倒横屏 (Inverted Landscape)")
    print("  [12] 🔓 解锁扭矩 (Unlock Torque)")
    print("  [13] 🔒 锁定扭矩 (Lock Torque)")
    print("  [14] 🎯 自定义角度 (Custom Angle)")
    print("-" * 50)
    print("  [15] 🏠 回到初始状态 / Reset to init position")
    print("  [16] 📤 发送自定义命令 / Send custom command")
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
            name = input("输入位置名称 / Enter position name: ").strip()
            if name:
                controller.save_position(name)
            else:
                print("❌ 名称不能为空 / Name cannot be empty")
        
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
        
        # Phone Holder Controls
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
            print("🔓 已发送: 解锁扭矩")
            
        elif choice == "13":
            controller.phone_torque(True)
            print("🔒 已发送: 锁定扭矩")
            
        elif choice == "14":
            try:
                angle = float(input("请输入角度 (0-360): ").strip())
                controller.phone_angle(angle)
                print(f"🎯 已发送: 转到 {angle}°")
            except ValueError:
                print("❌ 无效的角度数值")

        elif choice == "15":
            controller.move_to_init()

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
