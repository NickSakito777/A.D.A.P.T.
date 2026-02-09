#include <SCServo.h>

SMS_STS st;

// RoArm-M2-S default pins
#define S_RXD 18
#define S_TXD 19

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("==========================================");
  Serial.println("RoArm-M2-S Servo Scanner");
  Serial.println("==========================================");

  // Initialize Servo Serial at 1Mbps
  Serial1.begin(1000000, SERIAL_8N1, S_RXD, S_TXD);
  st.pSerial = &Serial1;
  while(!Serial1) {}

  Serial.println("Scanning IDs (0-253)...");
  Serial.println("------------------------------------------");
  int count = 0;
  for (int i = 0; i < 254; i++) {
    int result = st.Ping(i);
    Serial.print("ID ");
    Serial.print(i);
    Serial.print(": ");
    Serial.println(result != -1 ? "FOUND" : "-");
    if (result != -1) count++;
    delay(5);
  }

  Serial.println("------------------------------------------");
  Serial.print("Total servos found: ");
  Serial.println(count);
  Serial.println("==========================================");
}

void loop() {
  delay(1000);
}
