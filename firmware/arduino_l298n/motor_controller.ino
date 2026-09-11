#include <SoftwareSerial.h>

// =====================================================
// L298N
// =====================================================

// Левый мотор
#define L_IN1  4
#define L_IN2  5
#define L_PWM  6

// Правый мотор
#define R_IN1  7
#define R_IN2  8
#define R_PWM  9

// =====================================================
// UART от ESP32
// =====================================================

// Arduino RX <- ESP32 TX
#define ESP_RX 10

// Arduino TX -> ESP32 RX
// В нашем варианте НЕ используем, обратной связи нет
#define ESP_TX 11

SoftwareSerial espSerial(ESP_RX, ESP_TX);

// =====================================================
// Защита: если от ESP32 долго нет команд — глушим моторы.
// Согласовано с CMD_TIMEOUT_MS (1 c) на ESP32: держим запас,
// чтобы не резать мотори при штатной работе (команды идут каждые ~300мс).
// =====================================================

#define AUTO_STOP_MS 2000

unsigned long lastCmdAt = 0;

// =====================================================
// Настройки
// =====================================================

int speedPWM = 100;

// =====================================================
// Левый мотор
// power: -255 ... +255
// =====================================================

void leftMotor(int power)
{
  power = constrain(power, -255, 255);

  if (power > 0)
  {
    // Вперёд
    digitalWrite(L_IN1, HIGH);
    digitalWrite(L_IN2, LOW);
    analogWrite(L_PWM, power);
  }
  else if (power < 0)
  {
    // Назад
    digitalWrite(L_IN1, LOW);
    digitalWrite(L_IN2, HIGH);
    analogWrite(L_PWM, -power);
  }
  else
  {
    // Стоп
    digitalWrite(L_IN1, LOW);
    digitalWrite(L_IN2, LOW);
    analogWrite(L_PWM, 0);
  }
}

// =====================================================
// Правый мотор
// power: -255 ... +255
// =====================================================

void rightMotor(int power)
{
  power = constrain(power, -255, 255);

  if (power > 0)
  {
    // Вперёд
    digitalWrite(R_IN1, HIGH);
    digitalWrite(R_IN2, LOW);
    analogWrite(R_PWM, power);
  }
  else if (power < 0)
  {
    // Назад
    digitalWrite(R_IN1, LOW);
    digitalWrite(R_IN2, HIGH);
    analogWrite(R_PWM, -power);
  }
  else
  {
    // Стоп
    digitalWrite(R_IN1, LOW);
    digitalWrite(R_IN2, LOW);
    analogWrite(R_PWM, 0);
  }
}

// =====================================================
// Движение
// =====================================================

void forward()
{
  leftMotor(speedPWM);
  rightMotor(speedPWM);
}

void backward()
{
  leftMotor(-speedPWM);
  rightMotor(-speedPWM);
}

void turnLeft()
{
  leftMotor(-speedPWM);
  rightMotor(speedPWM);
}

void turnRight()
{
  leftMotor(speedPWM);
  rightMotor(-speedPWM);
}

void stopMotors()
{
  leftMotor(0);
  rightMotor(0);
}

// =====================================================
// Обработка команды
// =====================================================

void processCommand(String cmd)
{
  cmd.trim();

  if (cmd.length() == 0)
    return;

  // С этого момента есть живая команда от ESP32
  lastCmdAt = millis();

  // -----------------------------------------
  // Одна символьная команда
  // -----------------------------------------

  if (cmd == "F")
  {
    forward();
  }
  else if (cmd == "B")
  {
    backward();
  }
  else if (cmd == "L")
  {
    turnLeft();
  }
  else if (cmd == "R")
  {
    turnRight();
  }
  else if (cmd == "S")
  {
    stopMotors();
  }

  else if (cmd == "1")
  {
    leftMotor(speedPWM);
  }
  else if (cmd == "2")
  {
    leftMotor(-speedPWM);
  }
  else if (cmd == "3")
  {
    rightMotor(speedPWM);
  }
  else if (cmd == "4")
  {
    rightMotor(-speedPWM);
  }

  // -----------------------------------------
  // Установка скорости
  // Например: V150
  // -----------------------------------------

  else if (cmd.charAt(0) == 'V')
  {
    int value = cmd.substring(1).toInt();

    speedPWM = constrain(value, 0, 255);
  }
}

// =====================================================
// Чтение UART
// =====================================================

void readESP()
{
  static String command = "";

  while (espSerial.available())
  {
    char c = espSerial.read();

    // Команда заканчивается '\n'
    if (c == '\n')
    {
      processCommand(command);
      command = "";
    }
    else if (c != '\r')
    {
      command += c;
    }

    // Защита от слишком длинной команды
    if (command.length() > 32)
    {
      command = "";
    }
  }
}

// =====================================================
// Защита: если ESP32 "завис" (UART молчит), моторы должны встать.
// =====================================================

void safetyTimeout()
{
  if (millis() - lastCmdAt > AUTO_STOP_MS)
  {
    stopMotors();
    // Пере-армируем, чтобы не долбить analogWrite(0) каждую итерацию
    lastCmdAt = millis();
  }
}

// =====================================================
// SETUP
// =====================================================

void setup()
{
  // L298N
  pinMode(L_IN1, OUTPUT);
  pinMode(L_IN2, OUTPUT);
  pinMode(L_PWM, OUTPUT);

  pinMode(R_IN1, OUTPUT);
  pinMode(R_IN2, OUTPUT);
  pinMode(R_PWM, OUTPUT);

  // Стоп при запуске
  stopMotors();

  // Таймер защиты стартует с момента загрузки
  lastCmdAt = millis();

  // USB Serial — только для локальной отладки
  Serial.begin(115200);

  // UART от ESP32
  espSerial.begin(115200);

  Serial.println();
  Serial.println("=== ARDUINO MOTOR CONTROLLER ===");
  Serial.println("UART: ESP32 -> Arduino");
  Serial.println("No feedback");
}

// =====================================================
// LOOP
// =====================================================

void loop()
{
  readESP();
  safetyTimeout();
}