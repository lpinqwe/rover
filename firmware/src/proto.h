#pragma once

/*
 * Протокол связи (бинарный, компактный, для BLE).
 *
 * Все команды — один байтовый массив.
 *
 * Пакет команды (исходящий → ESP32), 5+ байт:
 *   [0]   = magic 0x52 ('R')
 *   [1]   = seq (счётчик команд у отправителя, монотонный)
 *   [2]   = cmd  (см. CMD_*)
 *   [3..] = payload (зависит от cmd)
 *   [N]   = checksum (XOR всех предыдущих)
 *
 * Пакет телеметрии (ESP32 → телефон), 5+ байт:
 *   [0] = magic 0x54 ('T')
 *   [1..] = поля (см. TelemetryFrame)
 *
 * ACK: 1 байт = seq последней выполненной команды (0x7F если нет).
 */

// --- Команды ---
enum Cmd : uint8_t {
  CMD_DRIVE      = 0x01,  // payload: int8 speed(-100..100), int8 steer(-100..100)
  CMD_STOP       = 0x02,  // payload: none
  CMD_LIGHT      = 0x03,  // payload: uint8 0/1
  CMD_MINE       = 0x04,  // payload: uint8 channel (0-based)
  CMD_LEG        = 0x05,  // payload: uint8 channel, int8 pos(-100..100)
  CMD_PING       = 0x06,  // payload: none
  CMD_RESET      = 0x07,  // payload: none
};

// --- Ответные коды (в телеметрии) ---
enum Ack : uint8_t {
  ACK_OK        = 0x00,
  ACK_UNKNOWN   = 0x01,
  ACK_BAD_CHK   = 0x02,
  ACK_BAD_SEQ   = 0x03,
  ACK_FALL      = 0x04,
  ACK_STOPPED   = 0x05,
};

// --- Структура телеметрии (после magic) ---
// byte 1   : status flags (bit0 = connected, bit1 = tilted, bit2 = watchdog stop)
// byte 2   : battery (volts * 10, uint8)
// byte 3   : left motor pwm (uint8, 0..100)
// byte 4   : right motor pwm (uint8, 0..100)
// byte 5   : mcu temp (int8 °C)
// byte 6   : ack seq (последняя выполненная команда)
// byte 7   : ack status (Ack)
// byte 8   : tilt angle (uint8 0..254  /277  => degree)
// byte 9   : reserved (leg positions 4bit+4bit)

struct DriveCmd {
  int8_t speed;  // -100..100
  int8_t steer;  // -100..100
};

constexpr uint8_t MAGIC_CMD = 0x52;
constexpr uint8_t MAGIC_TELEM = 0x54;

// Быстрый XOR-чексумма
inline uint8_t xsum(const uint8_t* data, size_t n) {
  uint8_t s = 0;
  for (size_t i = 0; i < n; i++) s ^= data[i];
  return s;
}