/*
 * ST3215 Servo ID Changer
 * Based on official Waveshare ST3215 documentation
 *
 * WARNING: Only connect ONE servo to the bus when changing ID!
 *          The servo MUST be powered with 12V externally.
 */

#include <SCServo.h>

SMS_STS st;

int ID_ChangeFrom = 1;   // Factory default is 1
int ID_Changeto   = 17;  // New ID

// RoArm-M2-S ESP32 default serial pins
#define S_RXD 18
#define S_TXD 19

void setup() {
  Serial1.begin(1000000, SERIAL_8N1, S_RXD, S_TXD);
  st.pSerial = &Serial1;
  while(!Serial1) {}

  st.unLockEprom(ID_ChangeFrom);                          // Unlock EPROM-SAFE
  st.writeByte(ID_ChangeFrom, SMS_STS_ID, ID_Changeto);   // Change ID
  st.LockEprom(ID_Changeto);                              // EPROM-SAFE is locked
}

void loop() {
}
