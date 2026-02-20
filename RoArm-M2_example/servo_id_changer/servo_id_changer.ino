/*
 * ST3215 Servo ID Changer + Move to 0°
 * WARNING: Only connect ONE servo to the bus!
 */

#include <SCServo.h>

SMS_STS st;

int ID_ChangeFrom = 1;   // Factory default
int ID_Changeto   = 18;  // New ID

#define S_RXD 18
#define S_TXD 19

void setup() {
  Serial1.begin(1000000, SERIAL_8N1, S_RXD, S_TXD);
  st.pSerial = &Serial1;
  while(!Serial1) {}

  // Step 1: Change ID
  st.unLockEprom(ID_ChangeFrom);
  st.writeByte(ID_ChangeFrom, SMS_STS_ID, ID_Changeto);
  st.LockEprom(ID_Changeto);

  // Step 2: Move to 0° (pos 0)
  delay(500);
  st.WritePosEx(ID_Changeto, 0, 500, 50);
}

void loop() {
}
