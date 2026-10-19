"""Log the Game Bub's console to a file (or stdout), one timestamped line
per log line, following the device across reboots and USB mode changes.

The firmware's console is a CDC port of its own USB device (normal and USB
drive modes). In USB Serial/JTAG mode (`usb_tool.py enable-debug`, for
flashing) the ESP32's built-in port carries it instead; that one is read
through `espflash monitor --before no-reset`: opening it directly can reset
the chip into its ROM download mode. Stop this logger before flashing.

Run from the dev shell (pyserial, espflash):
    python3 scripts/console_log.py --log gamebub.log
"""
import argparse
import glob
import subprocess
import sys
import time

import serial

CDC_GLOB = "/dev/serial/by-id/usb-*Game_Bub_Handheld*-if01"
JTAG_GLOB = "/dev/serial/by-id/usb-Espressif_USB_JTAG_serial_debug_unit_*-if00"


def emit(out, text: str) -> None:
    out.write(time.strftime("%H:%M:%S ") + text + "\n")
    out.flush()


def log_cdc(port: str, out) -> None:
    s = serial.Serial()
    s.port = port
    s.baudrate = 115200
    s.timeout = 0.5
    s.dtr = False
    s.rts = False
    s.open()
    emit(out, f"[logger] console {port}")
    pending = b""
    try:
        while True:
            data = s.read(4096)
            if not data:
                continue
            pending += data
            *lines, pending = pending.split(b"\n")
            for line in lines:
                emit(out, line.decode("utf-8", "replace").rstrip("\r"))
    except (serial.SerialException, OSError) as e:
        emit(out, f"[logger] disconnected: {e}")
    finally:
        try:
            s.close()
        except Exception:
            pass


def log_jtag(port: str, out) -> None:
    emit(out, f"[logger] espflash monitor {port}")
    proc = subprocess.Popen(
        ["espflash", "monitor", "--port", port, "--before", "no-reset", "--non-interactive"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    for line in proc.stdout:
        emit(out, line.decode("utf-8", "replace").rstrip("\r\n"))
    proc.wait()
    emit(out, f"[logger] espflash monitor exited ({proc.returncode})")


def main() -> None:
    parser = argparse.ArgumentParser(description="Log the Game Bub's console")
    parser.add_argument("--log", help="file to append to (default: stdout)")
    args = parser.parse_args()
    out = open(args.log, "a", encoding="utf-8") if args.log else sys.stdout

    while True:
        cdc = glob.glob(CDC_GLOB)
        jtag = glob.glob(JTAG_GLOB)
        if cdc:
            log_cdc(cdc[0], out)
        elif jtag:
            log_jtag(jtag[0], out)
        time.sleep(1)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
