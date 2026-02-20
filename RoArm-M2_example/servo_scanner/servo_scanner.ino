#include <SCServo.h>

SMS_STS st;

// RoArm-M2-S default pins
#define S_RXD 18
#define S_TXD 19

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("Scanning...");

  Serial1.begin(1000000, SERIAL_8N1, S_RXD, S_TXD);
  st.pSerial = &Serial1;
  while(!Serial1) {}

  int count = 0;
  for (int i = 0; i < 254; i++) {
    if (st.Ping(i) != -1) {
      Serial.print("Found ID: ");
      Serial.println(i);
      count++;
    }
    delay(5);
  }

  Serial.print("Total: ");
  Serial.println(count);
}

void loop() {
  delay(1000);
}
