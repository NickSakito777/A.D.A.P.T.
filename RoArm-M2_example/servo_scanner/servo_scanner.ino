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

  Serial.println("Start scanning IDs (0-253)...");
  int count = 0;
  for (int i = 0; i < 254; i++) {
    // Try to communicate with the servo
    // Using FeedBack as it is used in the main code to check presence
    if (st.FeedBack(i) != -1) {
      Serial.print("[FOUND] Servo ID: ");
      Serial.println(i);
      count++;
    }
    // Small delay to avoid bus congestion
    delay(5);
  }
  
  Serial.println("------------------------------------------");
  Serial.print("Scan Complete. Total servos found: ");
  Serial.println(count);
  Serial.println("==========================================");
  
  // Check against expected result (IDs 11-16)
  if(count == 6) {
      Serial.println("Result: SUCCESS (Found 6 servos)");
  } else {
      Serial.print("Result: WARNING (Expected 6, found ");
      Serial.print(count);
      Serial.println(")");
  }
}

void loop() {
  // Do nothing
  delay(1000);
}
