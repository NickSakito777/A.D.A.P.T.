#!/usr/bin/env python3
"""
自动调参 v7:
- 先关闭扭矩让用户放手机
- 然后从高扭矩逐渐降低，找到开始下坠的临界点
"""
import serial, json, time, subprocess, math

PORT = "/dev/cu.usbserial-1340"
subprocess.run(["stty", "-f", PORT, "-hupcl"], check=False)

ser = serial.Serial()
ser.port = PORT
ser.baudrate = 115200
ser.timeout = 2
ser.dsrdtr = False
ser.rtscts = False
ser.open()
ser.dtr = False
ser.rts = False
time.sleep(2)

def flush():
    ser.reset_input_buffer()
    time.sleep(0.05)
    while ser.in_waiting:
        ser.read(ser.in_waiting)
        time.sleep(0.02)

def read_pos():
    flush()
    ser.write(b'{"T":105}\n')
    ser.flush()
    time.sleep(0.6)
    while ser.in_waiting:
        line = ser.readline().decode('utf-8', errors='ignore').strip()
        if '1051' in line:
            try:
                d = json.loads(line)
                # Arduino infoFeedback() already returns degrees
                d['s_deg'] = d.get('s', 0)
                d['e_deg'] = d.get('e', 0)
                return d
            except:
                pass
    return None

def send(cmd):
    flush()
    ser.write((json.dumps(cmd) + '\n').encode())
    ser.flush()
    time.sleep(0.4)
    while ser.in_waiting:
        ser.readline()

def set_torque(s, e, b=50, h=50):
    send({"T": 112, "mode": 1, "b": b, "s": s, "e": e, "h": h})

def set_pid(p):
    for j in [1, 2, 3, 4]:
        send({"T": 108, "joint": j, "p": p, "i": 0})

def reset_all():
    send({"T": 112, "mode": 0, "b": 0, "s": 0, "e": 0, "h": 0})
    send({"T": 109})

def monitor(dur=3.0):
    """返回 (肩漂移度, 肘漂移度, 样本数, 样本列表)"""
    samples = []
    end = time.time() + dur
    while time.time() < end:
        p = read_pos()
        if p and 's_deg' in p:
            samples.append((p['s_deg'], p['e_deg'], p.get('torS', 0), p.get('torE', 0)))
    n = len(samples)
    if n < 2:
        return 999, 999, n, samples
    sd = abs(samples[-1][0] - samples[0][0])
    ed = abs(samples[-1][1] - samples[0][1])
    sr = max(x[0] for x in samples) - min(x[0] for x in samples)
    er = max(x[1] for x in samples) - min(x[1] for x in samples)
    return max(sd, sr), max(ed, er), n, samples

# ========== 开始 ==========
print("=== 自动调参 v7 ===\n")

p = read_pos()
if not p:
    print("连接失败"); ser.close(); exit(1)
print(f"当前: s={p['s_deg']:.1f}° e={p['e_deg']:.1f}° torS={p.get('torS',0)} torE={p.get('torE',0)}")

# 1. 确保手臂在展开位置
print("\n[1] 展开手臂 (肩-30°, 肘45°)...")
send({"T": 101, "joint": 2, "rad": -0.524, "spd": 500, "acc": 50})
time.sleep(3)
send({"T": 101, "joint": 3, "rad": 0.785, "spd": 500, "acc": 50})
time.sleep(3)
p = read_pos()
if p:
    print(f"  s={p['s_deg']:.1f}° e={p['e_deg']:.1f}°")

# 2. 关闭全部扭矩（手臂会软掉）
print("\n[2] 关闭扭矩 - 手臂会软掉")
print("    请在手臂软掉后放手机上去")
send({"T": 210, "cmd": 0})
print("    15秒后继续...")
for i in range(15, 0, -1):
    if i % 5 == 0:
        p = read_pos()
        if p:
            print(f"    {i}s: s={p['s_deg']:.1f}° e={p['e_deg']:.1f}°")
        else:
            print(f"    {i}s...")
    time.sleep(1)

# 3. 记录放手机后的位置（手臂在重力下的自然位置）
p = read_pos()
if p:
    rest_s = p['s_deg']
    rest_e = p['e_deg']
    print(f"\n  手臂自然位置: s={rest_s:.1f}° e={rest_e:.1f}°")
else:
    rest_s, rest_e = 0, 0

# 4. 从高扭矩开始，逐渐降低
# 先设800让手臂撑起来，然后逐步降
print("\n[3] 从高扭矩(800)逐渐降低...")
TH = 5.0  # 度

# 先以800启动
set_torque(800, 800)
set_pid(16)
time.sleep(3)
p = read_pos()
if p:
    hold_s = p['s_deg']
    hold_e = p['e_deg']
    print(f"  800扭矩hold位置: s={hold_s:.1f}° e={hold_e:.1f}° torS={p.get('torS',0)} torE={p.get('torE',0)}")

# 肩和肘分开测试
print(f"\n[4] 找肩部最小扭矩 (固定肘=800)")
best_s = 800
for s in [700, 600, 500, 450, 400, 350, 300, 250, 200, 150, 100]:
    set_torque(s, 800)
    time.sleep(2)
    sd, ed, n, samps = monitor(3.0)
    if n >= 2:
        cur_s = samps[-1][0]
        cur_e = samps[-1][1]
        # 跟hold位置比较偏移
        drift_s = abs(cur_s - hold_s)
        drift_e = abs(cur_e - hold_e)
        print(f"  肩={s}: 肩偏{drift_s:.1f}° 肘偏{drift_e:.1f}° (动态{sd:.1f}°/{ed:.1f}°) torS={samps[-1][2]}")
        if drift_s < TH and sd < TH:
            best_s = s
        else:
            print(f"    -> 下坠! 最小={best_s}")
            set_torque(best_s, 800)
            time.sleep(1)
            break
    else:
        print(f"  肩={s}: 样本不足")

safe_s = min(best_s + 50, 1000)
print(f"\n  >> 肩最小={best_s}, 安全={safe_s}")

# 恢复到hold位置
set_torque(safe_s, 800)
time.sleep(2)

print(f"\n[5] 找肘部最小扭矩 (固定肩={safe_s})")
# 先读hold位置
p = read_pos()
if p:
    hold_s = p['s_deg']
    hold_e = p['e_deg']

best_e = 800
for e in [700, 600, 500, 450, 400, 350, 300, 250, 200, 150, 100]:
    set_torque(safe_s, e)
    time.sleep(2)
    sd, ed, n, samps = monitor(3.0)
    if n >= 2:
        cur_s = samps[-1][0]
        cur_e = samps[-1][1]
        drift_s = abs(cur_s - hold_s)
        drift_e = abs(cur_e - hold_e)
        print(f"  肘={e}: 肩偏{drift_s:.1f}° 肘偏{drift_e:.1f}° (动态{sd:.1f}°/{ed:.1f}°) torE={samps[-1][3]}")
        if drift_e < TH and ed < TH and drift_s < TH:
            best_e = e
        else:
            print(f"    -> 下坠! 最小={best_e}")
            set_torque(safe_s, best_e)
            time.sleep(1)
            break
    else:
        print(f"  肘={e}: 样本不足")

safe_e = min(best_e + 50, 1000)
print(f"\n  >> 肘最小={best_e}, 安全={safe_e}")

# 6. PID
set_torque(safe_s, safe_e)
time.sleep(2)
p = read_pos()
if p:
    hold_s = p['s_deg']
    hold_e = p['e_deg']

print(f"\n[6] 降PID")
best_p = 16
for pp in [12, 8, 6, 4, 3, 2]:
    set_pid(pp)
    time.sleep(2)
    sd, ed, n, samps = monitor(3.0)
    if n >= 2:
        drift_s = abs(samps[-1][0] - hold_s)
        drift_e = abs(samps[-1][1] - hold_e)
        print(f"  PID={pp}: 肩偏{drift_s:.1f}° 肘偏{drift_e:.1f}° (动态{sd:.1f}°/{ed:.1f}°)")
        if drift_s < TH and drift_e < TH and sd < TH and ed < TH:
            best_p = pp
        else:
            print(f"    -> 不稳定!")
            set_pid(best_p)
            time.sleep(1)
            break
    else:
        print(f"  PID={pp}: 样本不足")

print(f"\n  >> PID={best_p}")

# 7. 恢复
print("\n[7] 恢复")
reset_all()

print(f"\n{'='*50}")
print(f"  结果: 肩={safe_s} 肘={safe_e} PID={best_p}")
print(f'  T:112: {{"T":112,"mode":1,"b":50,"s":{safe_s},"e":{safe_e},"h":50}}')
if best_p != 16:
    print(f'  T:108: {{"T":108,"joint":2,"p":{best_p},"i":0}}')
    print(f'         {{"T":108,"joint":3,"p":{best_p},"i":0}}')
print(f"{'='*50}")

ser.close()
