/*
 * Rover firmware — ESP32-S3 (Arduino)
 *
 * BLE-шлюз к телефону. Принимает команды (джойстик/кнопки),
 * отдаёт телеметрию, управляет моторами/фонариком/минами/ногами.
 *
 * Прошивка: 2 мотора (дифф. руление), драйвер — заглушка (см. MotorDriver.h).
 */

const char* FW_VERSION = "0.1.0";

#include <Arduino.h>
#include "config.h"
#include "proto.h"
#include "MotorDriver.h"
#include "Rover.h"

Rover rover;

void setup() {
  Serial.begin(115200);
  Serial.println();
  Serial.printf("[boot] Rover firmware v%s (ESP32-S3)\n", FW_VERSION);

  rover.begin();
}

void loop() {
  rover.update();
}
