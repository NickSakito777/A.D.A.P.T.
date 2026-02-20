/*
 * Dual Tilt Servo Sync Test
 * ID14 and ID18 - mirror movement using middle position (2047)
 * Same logic as shoulder joint ID12/ID13
 *
 * First: both move to middle pos (2047) = shared origin
 * Then:  rotate 160° using mirror formula
 *        ID14 = 2047 + offset
 *        ID18 = 2047 - offset
 */

#include <SCServo.h>

SMS_STS st;

#define S_RXD 18
#define S_TXD 19

#define MIDDLE_POS 2047

// 0° = both at middle
byte servoIDs[2]   = {14, 18};
s16  goalPos[2]    = {2047, 2047};
u16  moveSpd[2]    = {500, 500};
u8   moveAcc[2]    = {50, 50};

void setup() {
  Serial1.begin(1000000, SERIAL_8N1, S_RXD, S_TXD);
  st.pSerial = &Serial1;
  while(!Serial1) {}

  // Step 1: Both to middle position first
  s16 initPos[2] = {MIDDLE_POS, MIDDLE_POS};
  st.SyncWritePosEx(servoIDs, 2, initPos, moveSpd, moveAcc);
  delay(3000);

  // Step 2: Mirror rotate 160°
  st.SyncWritePosEx(servoIDs, 2, goalPos, moveSpd, moveAcc);
}

void loop() {
}
