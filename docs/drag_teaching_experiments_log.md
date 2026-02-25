# RoArm-M2-S Drag Teaching Mode — Experiment Log

**Date:** 2025-02-22
**Author:** Yikai Guo (with Claude AI assistance)
**Hardware:** RoArm-M2-S with dual motor shoulder (ID12+ID13) and dual motor elbow (ID14+ID18)
**Objective:** Implement a drag/teach mode that allows the user to manually push the robot arm to desired positions while the arm holds itself against gravity.

---

## Table of Contents

1. [Background & Motivation](#1-background--motivation)
2. [Approach 1: Hybrid State Machine (Arduino-side)](#2-approach-1-hybrid-state-machine-arduino-side)
3. [Critical Failure: Servo EEPROM Corruption](#3-critical-failure-servo-eeprom-corruption)
4. [Root Cause Analysis](#4-root-cause-analysis)
5. [Recovery & Full Rollback](#5-recovery--full-rollback)
6. [Approach 2: Python-side T:112 Dynamic Adaptation](#6-approach-2-python-side-t112-dynamic-adaptation)
7. [Automated Parameter Tuning Experiments](#7-automated-parameter-tuning-experiments)
8. [Findings & Current State](#8-findings--current-state)
9. [Lessons Learned](#9-lessons-learned)
10. [Appendix: Technical Reference](#10-appendix-technical-reference)

---

## 1. Background & Motivation

The RoArm-M2-S robot arm is used as a phone holder for video recording. A drag/teach mode is needed so the user can:

1. Manually push the arm to a desired position
2. Record that position
3. Replay recorded positions for automated camera movements

The arm uses Feetech SMS-STS serial bus servos. Each servo has both EEPROM (persistent) and SRAM (volatile) registers for configuration. The shoulder and elbow joints each use **dual motors** (driving + driven) with a `MIDDLE +/- offset` formula for coordinated control.

**Servo Layout:**
| Joint | Servo IDs | Type |
|-------|-----------|------|
| Base | ID=11 | Single |
| Shoulder | ID=12 (driving) + ID=13 (driven) | Dual |
| Elbow | ID=14 (driving) + ID=18 (driven) | Dual |
| Gripper/Hand | ID=15 | Single |

---

## 2. Approach 1: Hybrid State Machine (Arduino-side)

### 2.1 Concept

A state machine implemented in the Arduino firmware (C++) that cycles through three phases:

1. **TORQUE_OFF phase** (~200ms): Disable servo torque output, allowing the arm to be freely moved by hand.
2. **SENSING phase** (~50ms): Read servo positions via feedback registers to detect user-applied movement.
3. **TORQUE_ON phase** (~300ms): Re-enable torque and command servos to hold at the newly detected position with reduced torque (550 for shoulder, 450/500 for elbow).

The state machine was designed to run continuously in `loop()`, creating the illusion of a compliant arm by rapidly alternating between free movement and position holding.

### 2.2 Implementation Details

- Custom SRAM-only torque functions (`dragSetTorqueLimit()`, `dragSetShoulderTorque()`, `dragSetElbowTorque()`) were created to avoid EEPROM writes during rapid cycling.
- A cooldown mechanism (30 cycles, later increased to 50) was added to prevent state machine oscillation.
- Baseline position tracking was implemented to detect intentional movement vs. servo drift.
- PID P value was reduced from 16 (factory) to 2 to make the arm more compliant.

### 2.3 Problems Encountered

1. **Baseline captured from stale feedback**: After torque changes, the servo feedback position was read before the physical position had time to settle, causing incorrect baselines.
2. **State machine oscillation**: Rapid switching between torque-on and torque-off caused the arm to vibrate and jitter.
3. **EEPROM writes during transitions**: Despite using SRAM-only functions, `exitDragMode()` originally used EEPROM write functions, potentially corrupting persistent settings.
4. **Most critically**: The `WritePosEx()` function with `speed=0, acc=0` parameters changed the servo operation mode.

---

## 3. Critical Failure: Servo EEPROM Corruption

### 3.1 Symptoms

After running the hybrid state machine, **all four drag-mode servos (ID 12, 13, 14, 18) became completely unresponsive**. The arm was stuck and could not move. Rebooting and re-uploading firmware did not resolve the issue.

Serial output showed the system hanging at:
```
Moving SHOULDER_JOINT to initPos.
```
This indicated `waitMove2Goal()` was blocking indefinitely because the servos could not reach their target positions.

### 3.2 Diagnosis

A diagnostic block was added to `setup()` to read each servo's registers:

```cpp
byte ids[] = {11, 12, 13, 14, 15, 18};
for(int i=0; i<6; i++){
    int mode = st.ReadMode(ids[i]);
    Serial.print("ID="); Serial.print(ids[i]);
    Serial.print(" mode="); Serial.println(mode);
}
```

**Output revealed:**
```
ID=11 mode=0   ← OK (position mode)
ID=12 mode=2   ← CORRUPTED (PWM open-loop mode!)
ID=13 mode=2   ← CORRUPTED
ID=14 mode=2   ← CORRUPTED
ID=15 mode=0   ← OK
ID=18 mode=2   ← CORRUPTED
```

All four servos used by the drag teaching mode had their **MODE register (EEPROM address 33)** changed from `0` (position servo mode) to `2` (PWM open-loop mode).

### 3.3 Additional Corruption

The torque limit registers were also corrupted:
- Shoulder servos: torque limit reduced to ~550 (from 1000)
- Elbow servos: torque limit reduced to ~450 (from 1000)

These values were written to EEPROM and persisted across power cycles.

---

## 4. Root Cause Analysis

### 4.1 Mode Corruption

The root cause was identified as `WritePosEx(ID, position, 0, 0)` being called with **speed=0 and acceleration=0**.

In the SMS-STS servo protocol, writing a position command with speed=0 causes the servo firmware to interpret this as a mode change to PWM open-loop mode (mode=2). This is written to EEPROM address 33 (`SMS_STS_MODE`) and persists permanently.

The hybrid state machine's TORQUE_ON phase called `WritePosEx` with speed and acceleration values derived from the state machine parameters, which under certain conditions evaluated to 0.

### 4.2 Torque Limit Corruption

The drag mode explicitly set reduced torque limits (550/450) using functions that called `unLockEprom()` + `writeWord()` + `LockEprom()`, which writes directly to EEPROM. During rapid state cycling, this corrupted the persistent torque limit values.

### 4.3 Why `waitMove2Goal()` Hung

With servos in PWM mode (mode=2), the `WritePosEx()` position commands have no effect — the servo does not move to a target position. The `waitMove2Goal()` function contains a `while` loop that waits for the servo to reach the target position, resulting in an infinite loop.

---

## 5. Recovery & Full Rollback

### 5.1 EEPROM Repair

The following repair code was added to `setup()` in the Arduino firmware:

**Torque Limit Fix** — Write `TORQUE_LIMIT=1000` to each servo's EEPROM:
```cpp
st.unLockEprom(SERVO_ID);
st.writeWord(SERVO_ID, SMS_STS_TORQUE_LIMIT_L, ST_TORQUE_MAX);
st.LockEprom(SERVO_ID);
```

**Mode Fix** — Write `MODE=0` (position servo) to each corrupted servo's EEPROM:
```cpp
st.unLockEprom(SERVO_ID);
st.writeByte(SERVO_ID, SMS_STS_MODE, 0);
st.LockEprom(SERVO_ID);
```

This was applied to all 6 servos (IDs 11, 12, 13, 14, 15, 18) during boot.

### 5.2 Code Rollback

All files were rolled back to git commit `5f66738` ("Backup before drag teaching mode implementation"):

- `RoArm-M2_config.h` — Removed all drag state machine structs and constants
- `RoArm-M2_module.h` — Removed all drag mode functions
- `RoArm-M2_example.ino` — Restored original `loop()`, added EEPROM repair in `setup()`
- `uart_ctrl.h` — Restored original `T:210` handler
- `roarm_position_tool_4.py` — Removed threading, listener, and drag menu

### 5.3 Verification

After uploading the repaired firmware, diagnostic output confirmed:
```
ID=12 mode=0  ← Fixed
ID=13 mode=0  ← Fixed
ID=14 mode=0  ← Fixed
ID=18 mode=0  ← Fixed
```

User confirmed: "ok终于回来了" (finally back).

---

## 6. Approach 2: Python-side T:112 Dynamic Adaptation

### 6.1 Concept

Instead of modifying Arduino firmware, use the existing `T:112` command (`RoArmM2_dynamicAdaptation()`) to set reduced torque limits from the Python side. This approach:

- Requires **zero Arduino code changes** (eliminating EEPROM corruption risk)
- Uses a one-shot command rather than a continuous state machine loop
- Is invoked at the end of the `torque_off()` function in the Python tool

### 6.2 Implementation

In `roarm_position_tool_4.py`, the `torque_off()` function was modified to:

1. Move to the folded/safe position ("torque closed")
2. Disable all torque (`T:210 cmd:0`)
3. Set reduced torque limits via `T:112`:

```python
self.send_command({"T": 112, "mode": 1, "b": 50, "s": 500, "e": 500, "h": 50})
```

Parameters: Base=50, Shoulder=500, Elbow=500, Hand=50.

### 6.3 How T:112 Works Internally

The `RoArmM2_dynamicAdaptation()` function calls per-joint torque control functions:

```cpp
void RoArmM2_dynamicAdaptation(byte inputM, int inputB, int inputS, int inputE, int inputH) {
    if (inputM == 0) {
        // Reset all to 1000
    } else if (inputM == 1) {
        RoArmM2_baseTorqueCtrl(inputB);
        RoArmM2_shoulderTorqueCtrl(inputS);
        RoArmM2_elbowTorqueCtrl(inputE);
        RoArmM2_handTorqueCtrl(inputH);
    }
}
```

**Important note:** These torque control functions use `unLockEprom()` + `writeWord()` + `LockEprom()`, which writes to EEPROM. However, since `T:112` is a one-shot command (not called in a loop), the EEPROM write is acceptable.

### 6.4 Limitations

- The PID controller is still active, so the servo actively resists external forces. The torque limit only caps how much force the motor can apply.
- To make the arm easier to push, the PID P gain also needs to be reduced (via `T:108` command).
- Finding the optimal combination of torque limit and PID P value requires manual tuning.

---

## 7. Automated Parameter Tuning Experiments

### 7.1 Objective

An automated tuning script (`auto_tune.py`) was developed to systematically find the optimal torque and PID parameters by:

1. Connecting to the arm via serial port (`/dev/cu.usbserial-1340`)
2. Commanding the arm to various poses
3. Setting different torque/PID combinations
4. Monitoring position feedback to detect arm drop/drift

### 7.2 Technical Challenges Encountered

#### 7.2.1 Serial Port DTR Reset

**Problem:** On macOS, opening a serial port triggers a DTR (Data Terminal Ready) signal change, which resets the ESP32 microcontroller. This causes the firmware's `setup()` to run again (including TORQUE FIX, DIAGNOSTIC, MODE FIX, and `moveInit()`), taking several seconds.

**Solution:** Use `stty -hupcl` to disable hangup-on-close, and configure pyserial with `dsrdtr=False`:

```python
subprocess.run(["stty", "-f", PORT, "-hupcl"])
ser = serial.Serial()
ser.dsrdtr = False
ser.rtscts = False
ser.open()
ser.dtr = False
ser.rts = False
```

#### 7.2.2 Blocking Movement Commands

**Problem:** The `T:104` command (Bessel interpolated movement) calls `RoArmM2_movePosGoalfromLast()`, which contains a blocking `for` loop with `delay(2)` per step. For large movements, this blocks the ESP32 for 5-15 seconds, during which it cannot respond to serial commands.

**Solution:** Use `T:101` (single joint control) or `T:1041` (direct XYZ control) instead, which call `SyncWritePosEx()` without blocking loops.

#### 7.2.3 Radians vs. Degrees Bug

**Problem:** The position feedback fields `s` and `e` in the `T:1051` response are in **radians**, not degrees. The initial tuning script compared these values against a threshold of 3.0, which in radians equals 172 degrees — making it nearly impossible to detect arm drop.

**Solution:** Convert to degrees before comparison:

```python
d['s_deg'] = math.degrees(d.get('s', 0))
d['e_deg'] = math.degrees(d.get('e', 0))
```

### 7.3 Tuning Results

With the phone mounted on the arm, tests were conducted across multiple arm poses:

| Pose | Shoulder Angle | Elbow Angle | Min Stable Torque | Notes |
|------|----------------|-------------|-------------------|-------|
| Folded | -1.4° (rad: -0.02) | 1.7° (rad: 0.03) | <50 | Gravity load negligible |
| Extended (s=-30° e=45°) | -27.9° | 44.0° | <50 | Dual motors provide ample torque |
| Horizontal (s=-60° e=90°) | -59.5° | 83.7° | <50 | Still stable at minimum |
| Forward reach (s=-45° e=60°) | -44.5° | 58.4° | <50 | torE near zero at lowest |

**Key finding:** The dual-motor design provides sufficient torque to hold the arm + phone even at torque limits as low as 50 (5% of maximum). The PID position controller continues to maintain the target position within its torque budget.

**Combined torque + PID tests (at forward reach pose):**

| Torque | PID P | Shoulder Drift | Elbow Drift | Result |
|--------|-------|---------------|-------------|--------|
| 50 | 2 | 0.2° | 0.1° | Stable |
| 80 | 2 | 0.2° | 0.1° | Stable |
| 100 | 2 | 0.2° | 0.1° | Stable |
| 50 | 4 | 0.2° | 0.0° | Stable |
| 50 | 8 | 0.2° | 0.0° | Stable |
| 80 | 8 | 0.2° | 0.0° | Stable |

All tested combinations were stable. This indicates that the automated approach alone is insufficient for tuning the "feel" of the arm — the parameters need to be tuned by hand based on how easy the arm is to push, which is a subjective, tactile experience.

### 7.4 Conclusion on Automated Tuning

While the automated tuning successfully identified that the arm can hold its position at very low torque values, it **cannot evaluate the practical usability** of the teach mode. The "pushability" of the arm depends on:

- How quickly the PID responds to external forces
- How much resistance the user feels when pushing
- Whether the arm oscillates or overshoots after being released

These are subjective, haptic qualities that require manual adjustment by the user.

---

## 8. Findings & Current State

### 8.1 What Works

- **T:112 command** safely sets torque limits without risking EEPROM corruption (as a one-shot command)
- **T:108 command** can set per-joint PID P values at runtime (writes to SRAM, safe)
- **T:109 command** resets PID to factory default (P=16)
- The arm's dual-motor system provides robust holding force even at very low torque limits

### 8.2 Current Code State

**Arduino firmware** (commit `5f66738` + EEPROM repair):
- All drag teaching code removed
- `setup()` contains TORQUE FIX, DIAGNOSTIC, and MODE FIX blocks as safety measures
- Original `loop()` with `constantHandle()` restored

**Python tool** (`roarm_position_tool_4.py`):
- `torque_off()` includes `T:112` call at the end with `b=50, s=500, e=500, h=50`
- `torque_on()` sends `T:210 cmd=1` to re-enable torque
- No PID modification is currently applied (PID remains at firmware default P=16)

### 8.3 Parameters for Manual Tuning

The user needs to manually adjust these values based on feel:

| Parameter | Command | Current Value | Range | Effect |
|-----------|---------|---------------|-------|--------|
| Shoulder torque | T:112 `s` field | 500 | 50-1000 | Higher = harder to push, better gravity resistance |
| Elbow torque | T:112 `e` field | 500 | 50-1000 | Same as above |
| Base torque | T:112 `b` field | 50 | 50-1000 | Resistance to rotation |
| Gripper torque | T:112 `h` field | 50 | 50-1000 | Resistance to wrist movement |
| PID P (all joints) | T:108 `p` field | 16 (default) | 1-32 | Lower = softer/more compliant |

---

## 9. Lessons Learned

### 9.1 Never Use `WritePosEx()` with speed=0

Calling `WritePosEx(ID, pos, 0, 0)` on SMS-STS servos changes the operation mode to PWM open-loop (mode=2), which is **written to EEPROM and persists across power cycles**. This is undocumented behavior that caused a complete servo brick.

**Prevention:** Always validate speed and acceleration parameters before calling `WritePosEx()`. Use a minimum speed of 1.

### 9.2 EEPROM vs SRAM Awareness

| Register | Address | Storage | Safe for Frequent Writes? |
|----------|---------|---------|--------------------------|
| MODE | 33 | EEPROM | No — persist permanently |
| PID P | 21 | EEPROM | No, but `writeByte` without `unLockEprom` writes SRAM only |
| TORQUE_ENABLE | 40 | SRAM | Yes — resets on reboot |
| TORQUE_LIMIT | 48-49 | Both | Dangerous — `unLockEprom+writeWord+LockEprom` writes EEPROM |

**Rule:** Never write to EEPROM in a loop. One-shot writes during setup or user commands are acceptable.

### 9.3 Blocking Functions in Arduino Firmware

`T:104` (Bessel movement) and `moveInit()` contain blocking loops that prevent serial command processing. When developing interactive features, use non-blocking alternatives:

- `T:1041` (direct position control) — non-blocking
- `T:101` (single joint control) — non-blocking
- `SyncWritePosEx()` — non-blocking

### 9.4 Serial Communication on macOS

Opening a serial port on macOS triggers DTR, which resets ESP32. Mitigations:
- `stty -f /dev/cu.usbserial-XXXX -hupcl`
- `dsrdtr=False` in pyserial
- Allow 3+ seconds after opening for `setup()` to complete

### 9.5 Automated Tuning Has Limits

While automated position monitoring can identify stability thresholds, the "feel" of a teach mode is inherently subjective. The optimal parameters depend on the user's preferences for:
- Resistance when pushing
- How quickly the arm settles after release
- Whether slight oscillation is acceptable

These require hands-on manual tuning.

---

## 10. Appendix: Technical Reference

### 10.1 Key JSON Commands

```json
// Read position feedback (response: T:1051 with s, e in radians)
{"T": 105}

// Move to init position (BLOCKING — takes several seconds)
{"T": 100}

// Single joint control (non-blocking)
// joint: 1=base, 2=shoulder, 3=elbow, 4=gripper
{"T": 101, "joint": 2, "rad": -0.524, "spd": 500, "acc": 50}

// XYZ Bessel movement (BLOCKING)
{"T": 104, "x": 150, "y": 0, "z": 150, "t": 3.14, "spd": 0.25}

// XYZ direct movement (non-blocking)
{"T": 1041, "x": 150, "y": 0, "z": 150, "t": 3.14}

// Set per-joint PID
{"T": 108, "joint": 2, "p": 4, "i": 0}

// Reset PID to factory (P=16, I=0)
{"T": 109}

// Torque on/off (all servos)
{"T": 210, "cmd": 0}  // off
{"T": 210, "cmd": 1}  // on

// Dynamic adaptation (set torque limits)
{"T": 112, "mode": 1, "b": 50, "s": 500, "e": 500, "h": 50}  // set
{"T": 112, "mode": 0, "b": 0, "s": 0, "e": 0, "h": 0}        // reset to 1000
```

### 10.2 SMS-STS Servo Register Map (Relevant)

| Address | Name | Region | Description |
|---------|------|--------|-------------|
| 21 | PID_P | EEPROM | Proportional gain (default: 16) |
| 33 | MODE | EEPROM | 0=position, 1=speed, 2=PWM |
| 40 | TORQUE_ENABLE | SRAM | 0=disabled, 1=enabled |
| 48-49 | TORQUE_LIMIT | SRAM* | Max torque output (0-1000) |

*Note: TORQUE_LIMIT is in SRAM, but using `unLockEprom()` + `writeWord()` + `LockEprom()` also writes to EEPROM.

### 10.3 File Inventory

| File | Status | Description |
|------|--------|-------------|
| `RoArm-M2_example.ino` | Rolled back + EEPROM repair | Main Arduino sketch |
| `RoArm-M2_config.h` | Rolled back to 5f66738 | Configuration and constants |
| `RoArm-M2_module.h` | Rolled back to 5f66738 | Motor control functions |
| `uart_ctrl.h` | Rolled back to 5f66738 | Serial command handler |
| `roarm_position_tool_4.py` | Modified | Python CLI tool with T:112 in torque_off() |
| `auto_tune.py` | Experimental (not in use) | Automated parameter tuning script |

---

*End of document.*
