//! The PSX core's disc reader: the program of the sub-CPU in the glue
//! (`psx.PsxDisc`), which reads the disc image through the firmware's
//! on-demand file requests (any firmware with them, stock included).
//!
//! When the core is first started (CoreRun) it is held in reset while the
//! reader finds the image (the cue sheet named like the selected file, else
//! the image alone), parses it and reads the region from the license
//! sector. From then on it gives the core its track table each time the
//! core leaves reset, and reads each sector the core asks for into the
//! sector buffer, which the glue streams into the core. When the firmware
//! signals another disc (a file write to the disc's ID, as the pause menu's
//! disc change does), it reads the new one and gives the running core its
//! track table: the core's drive takes that as a disc change.
//!
//! Memory map (see `PsxDisc` in the glue for the registers):
//! 0x0000_0000 RAM (this program), 0x0100_0000 the sector buffer (which the
//! firmware writes at host address 0x7000_0000), 0x0200_0000 registers.

#![no_std]
#![no_main]

use core::ptr::{read_volatile, write_volatile};

use psx_disc::{cooked_header, CueParser, Disc, Error, LineSplitter, Location, Region, COOKED_OFFSET, COOKED_SIZE, LICENSE_SECTOR, SECTOR_SIZE};

core::arch::global_asm!(
    ".section .text.start, \"ax\"",
    ".global _start",
    "_start:",
    "  la sp, _stack_top",
    "  la t0, _sbss",
    "  la t1, _ebss",
    "1:",
    "  bgeu t0, t1, 2f",
    "  sw zero, 0(t0)",
    "  addi t0, t0, 4",
    "  j 1b",
    "2:",
    "  call main",
    "3:",
    "  j 3b",
);

// File IDs (fpga/cores/psx/files.json).
/// The cue sheet named like the selected disc file (the selected file itself
/// when that is a cue sheet).
const FILE_CUE: u32 = 10;
/// The image named like the selected disc file (the selected file itself
/// when that is a `.bin`).
const FILE_BIN: u32 = 11;

// Core -> host commands (`HostV0`) and their status codes.
const CMD_FILE_GET_SIZE: u32 = 0x0400;
const CMD_FILE_READ: u32 = 0x0402;
const FILE_STATUS_OK: u32 = 0;

/// Where the firmware writes for the sector buffer (the glue's host map).
const SECTOR_HOST_ADDRESS: u32 = 0x7000_0000;
const SECTOR_BUFFER: usize = 0x0100_0000;
const SECTOR_BUFFER_BYTES: u32 = 4096;
/// The largest cue sheet read.
const MAX_CUE: u64 = 64 * 1024;

mod reg {
    const BASE: usize = 0x0200_0000;
    /// The six request words: the command and five arguments; the
    /// firmware's three result words come back in the first three.
    pub const CMD_WORDS: usize = BASE;
    /// Write bit 0: the request. Read: 0 request, 1 busy, 2 done, 3 error.
    pub const CMD_CTRL: usize = BASE + 0x18;
    /// Sticky events (write ones to clear; the sector request clears when
    /// its sector is streamed).
    pub const EVENTS: usize = BASE + 0x20;
    /// 0: the core is in reset; 1: a sector is being streamed into it.
    pub const STATUS: usize = BASE + 0x24;
    /// The LBA the core asks for.
    pub const HPS_LBA: usize = BASE + 0x28;
    /// Write: stream the sector buffer into the core (bit 0: count it as a
    /// failed read).
    pub const STREAM: usize = BASE + 0x2C;
    pub const TRACKINFO_DATA: usize = BASE + 0x30;
    /// Write: the core's track table word at this address gets TRACKINFO_DATA.
    pub const TRACKINFO_ADDR: usize = BASE + 0x34;
    /// 0: hold the core in reset; 1: a disc is in; 4: region known, 6-5 region.
    pub const CONTROL: usize = BASE + 0x38;
    /// Cycles of the core's system clock (33.9 MHz), free-running.
    pub const TIMER: usize = BASE + 0x3C;
    /// Read by the host (debug): see `Debug`.
    pub const DEBUG: usize = BASE + 0x40;
    pub const DEBUG_LBA: usize = BASE + 0x44;

    pub const CTRL_REQUEST: u32 = 1 << 0;
    pub const CTRL_BUSY: u32 = 1 << 1;
    pub const CTRL_DONE: u32 = 1 << 2;
    pub const CTRL_ERROR: u32 = 1 << 3;

    pub const EV_START: u32 = 1 << 0;
    pub const EV_LEFT_RESET: u32 = 1 << 1;
    pub const EV_DISC_CHANGED: u32 = 1 << 2;
    pub const EV_SECTOR: u32 = 1 << 3;

    pub const STATUS_CORE_RESET: u32 = 1 << 0;
    pub const STATUS_STREAMING: u32 = 1 << 1;

    pub const CONTROL_HOLD: u32 = 1 << 0;
    pub const CONTROL_DISC: u32 = 1 << 1;
    pub const CONTROL_REGION_VALID: u32 = 1 << 4;
    pub const CONTROL_REGION_SHIFT: u32 = 5;
}

const CLOCK_HZ: u32 = 33_868_800;
/// A request the firmware has not taken (busy) in this time is raised
/// again, in case its interrupt was missed.
const REQUEST_RETRY_CYCLES: u32 = 3 * CLOCK_HZ;
/// Attempts at a sector read the firmware fails.
const READ_ATTEMPTS: u32 = 3;

fn rd(address: usize) -> u32 {
    unsafe { read_volatile(address as *const u32) }
}

fn wr(address: usize, value: u32) {
    unsafe { write_volatile(address as *mut u32, value) }
}

fn timer() -> u32 {
    rd(reg::TIMER)
}

fn wait(cycles: u32) {
    let start = timer();
    while timer().wrapping_sub(start) < cycles {}
}

/// The debug register: bits 7-0 the phase, 15-8 the last error (an
/// [`Error`] code, or 0x80 + a failed request's file status), 16 a disc
/// is in, 17 the region is known, 19-18 the region; bit 31 is the CPU's
/// trap (set by the glue).
#[derive(Copy, Clone)]
struct Debug {
    phase: u8,
    error: u8,
    disc: bool,
    region: Option<Region>,
}

impl Debug {
    fn show(&self) {
        let region = match self.region {
            None => 0,
            Some(r) => 0b100 | r as u32,
        };
        wr(
            reg::DEBUG,
            self.phase as u32 | (self.error as u32) << 8 | (self.disc as u32) << 16 | region << 17,
        );
    }
}

mod phase {
    pub const IDLE: u8 = 1;
    pub const LOADING: u8 = 2;
    pub const REGION: u8 = 3;
    pub const RUNNING: u8 = 4;
    pub const TRACKINFO: u8 = 5;
    pub const SECTOR: u8 = 6;
    pub const PANIC: u8 = 0xFF;
}

/// A core -> host request: the command and its arguments; the result words
/// (the first is the file status), or `Err` with them if the firmware
/// answered with an error.
fn request(command: u32, args: &[u32]) -> Result<[u32; 3], [u32; 3]> {
    // The firmware's answer to the last request is cleared once that
    // request is dropped.
    while rd(reg::CMD_CTRL) & (reg::CTRL_BUSY | reg::CTRL_DONE | reg::CTRL_ERROR) != 0 {}
    wr(reg::CMD_WORDS, command);
    for (i, &a) in args.iter().enumerate() {
        wr(reg::CMD_WORDS + 4 * (i + 1), a);
    }
    for i in args.len()..5 {
        wr(reg::CMD_WORDS + 4 * (i + 1), 0);
    }
    wr(reg::CMD_CTRL, reg::CTRL_REQUEST);
    let mut since = timer();
    loop {
        let status = rd(reg::CMD_CTRL);
        if status & reg::CTRL_DONE != 0 {
            let result = [rd(reg::CMD_WORDS), rd(reg::CMD_WORDS + 4), rd(reg::CMD_WORDS + 8)];
            wr(reg::CMD_CTRL, 0);
            return if status & reg::CTRL_ERROR != 0 || result[0] != FILE_STATUS_OK { Err(result) } else { Ok(result) };
        }
        if status & reg::CTRL_BUSY == 0 && timer().wrapping_sub(since) > REQUEST_RETRY_CYCLES {
            // Not taken: raise it again (a new edge for the interrupt).
            wr(reg::CMD_CTRL, 0);
            wait(1000);
            wr(reg::CMD_CTRL, reg::CTRL_REQUEST);
            since = timer();
        }
    }
}

/// The size of file `id`, if it can be opened.
fn file_size(id: u32) -> Option<u64> {
    request(CMD_FILE_GET_SIZE, &[id]).ok().map(|r| r[1] as u64 | (r[2] as u64) << 32)
}

/// Read `length` bytes of file `id` at `offset` into the sector buffer at
/// `at`; returns the bytes read (fewer at the end of the file).
fn file_read(id: u32, offset: u32, at: u32, length: u32) -> Result<u32, u32> {
    request(CMD_FILE_READ, &[id, SECTOR_HOST_ADDRESS + at, offset, 0, length])
        .map(|r| r[1].min(length))
        .map_err(|r| 0x80 | (r[0] & 0x7F))
}

fn buffer_byte(offset: u32) -> u8 {
    unsafe { read_volatile((SECTOR_BUFFER + offset as usize) as *const u8) }
}

fn buffer_bytes(offset: u32, length: u32) -> impl Iterator<Item = u8> {
    (offset..offset + length).map(buffer_byte)
}

/// Zero the sector buffer from `from` to `to` (bytes; words where aligned).
fn buffer_clear(mut from: u32, to: u32) {
    while from < to && from % 4 != 0 {
        unsafe { write_volatile((SECTOR_BUFFER + from as usize) as *mut u8, 0) };
        from += 1;
    }
    while from + 4 <= to {
        wr(SECTOR_BUFFER + from as usize, 0);
        from += 4;
    }
    while from < to {
        unsafe { write_volatile((SECTOR_BUFFER + from as usize) as *mut u8, 0) };
        from += 1;
    }
}

fn buffer_write(offset: u32, bytes: &[u8]) {
    for (i, &b) in bytes.iter().enumerate() {
        unsafe { write_volatile((SECTOR_BUFFER + offset as usize + i) as *mut u8, b) };
    }
}

/// Find the image and parse it: the cue sheet named like the selected file
/// if there is one, with the image named like it; else that image alone.
/// (The cue sheet first: the firmware keeps one file open, and the image,
/// which it reads next, is the slow one to open.)
fn load_disc(disc: &mut Disc) -> Result<(), Error> {
    let Some(cue_size) = file_size(FILE_CUE) else {
        return disc.set_single(file_size(FILE_BIN).ok_or(Error::NoImage)?);
    };
    if cue_size > MAX_CUE {
        return Err(Error::CueTooLarge);
    }
    let mut parser = CueParser::new(disc);
    let mut lines = LineSplitter::new();
    // The firmware's transfers arrive in whole words (a partial last word
    // never reaches the buffer): whole words at a time, and the last few
    // bytes from the file's last word.
    let size = cue_size as u32;
    let mut offset = 0u32;
    while offset < size {
        let remaining = size - offset;
        if remaining < 4 {
            let n = file_read(FILE_CUE, size - 4, 0, 4).map_err(|_| Error::CueReadFailed)?;
            if n == 4 {
                lines.feed(buffer_bytes(4 - remaining, remaining), |l| parser.line(l))?;
            }
            break;
        }
        let n = file_read(FILE_CUE, offset, 0, SECTOR_BUFFER_BYTES).map_err(|_| Error::CueReadFailed)? & !3;
        if n == 0 {
            break;
        }
        lines.feed(buffer_bytes(0, n), |l| parser.line(l))?;
        offset += n;
    }
    lines.end(|l| parser.line(l))?;
    parser.finish(file_size(FILE_BIN).ok_or(Error::NoImage)?)
}

/// Read the sector at `lba` into the buffer, raw (made up around the data
/// of a 2048-byte sector). On a failed read the buffer holds what arrived,
/// zeros for the rest.
fn read_sector(disc: &Disc, lba: u32) -> Result<(), u32> {
    while rd(reg::STATUS) & reg::STATUS_STREAMING != 0 {}
    let (offset, at, length) = match disc.locate(lba) {
        Location::Zero => {
            buffer_clear(0, SECTOR_SIZE);
            return Ok(());
        }
        Location::Raw(offset) => (offset, 0, SECTOR_SIZE),
        Location::Cooked(offset) => (offset, COOKED_OFFSET, COOKED_SIZE),
    };
    let mut result = Err(0);
    let mut got = 0;
    for _ in 0..READ_ATTEMPTS {
        match file_read(FILE_BIN, offset, at, length) {
            Ok(n) => {
                got = n;
                result = Ok(());
                break;
            }
            Err(e) => result = Err(e),
        }
    }
    buffer_clear(at + got, at + length);
    if at != 0 {
        buffer_write(0, &cooked_header(lba));
        buffer_clear(at + length, SECTOR_SIZE);
    }
    result
}

/// Give the core its track table, if it is out of reset.
fn send_trackinfo(disc: &Disc, region: Option<Region>) {
    for (address, word) in disc.trackinfo(region) {
        if rd(reg::STATUS) & reg::STATUS_CORE_RESET != 0 {
            // Back in reset: it gets the table when it leaves.
            return;
        }
        wr(reg::TRACKINFO_DATA, word);
        wr(reg::TRACKINFO_ADDR, address);
    }
}

/// Read the disc into `disc`; whether it can be played.
fn load(disc: &mut Disc, debug: &mut Debug) -> bool {
    debug.phase = phase::LOADING;
    debug.show();
    match load_disc(disc) {
        Ok(()) => {
            debug.disc = true;
            true
        }
        Err(e) => {
            debug.error = e as u8;
            debug.disc = false;
            false
        }
    }
}

fn control(hold: bool, disc: bool, region: Option<Region>) {
    let region = match region {
        None => 0,
        Some(r) => reg::CONTROL_REGION_VALID | (r as u32) << reg::CONTROL_REGION_SHIFT,
    };
    wr(reg::CONTROL, (hold as u32) * reg::CONTROL_HOLD | (disc as u32) * reg::CONTROL_DISC | region);
}

/// The reader's state, outside the stack frame of `main` (a disc is 2.8 KiB).
static mut DISC: Disc = Disc::empty();

#[no_mangle]
extern "C" fn main() -> ! {
    // SAFETY: the only reference to it, from here on.
    let disc = unsafe { &mut *core::ptr::addr_of_mut!(DISC) };
    let mut loaded = false;
    let mut started = false;
    let mut region: Option<Region> = None;
    let mut debug = Debug { phase: phase::IDLE, error: 0, disc: false, region: None };
    debug.show();
    // The core stays held (the reset value) until the disc has been read.

    loop {
        let events = rd(reg::EVENTS);
        if events & reg::EV_START != 0 {
            wr(reg::EVENTS, reg::EV_START);
            if !started {
                started = true;
                loaded = load(disc, &mut debug);
                if loaded {
                    debug.phase = phase::REGION;
                    debug.show();
                    if read_sector(disc, LICENSE_SECTOR).is_ok() {
                        region = psx_disc::region(SECTOR_SIZE as usize, |i| buffer_byte(i as u32));
                    }
                    debug.region = region;
                }
                debug.phase = phase::RUNNING;
                debug.show();
                control(false, loaded, region);
            }
        }
        if events & reg::EV_DISC_CHANGED != 0 {
            wr(reg::EVENTS, reg::EV_DISC_CHANGED);
            // Another disc: its track table goes to the core if it runs (if
            // in reset, when it leaves); the region stays the first disc's.
            loaded = load(disc, &mut debug);
            if started {
                control(false, loaded, region);
                if loaded {
                    send_trackinfo(disc, region);
                }
            }
            debug.phase = phase::RUNNING;
            debug.show();
        }
        if events & reg::EV_LEFT_RESET != 0 {
            wr(reg::EVENTS, reg::EV_LEFT_RESET);
            if loaded {
                debug.phase = phase::TRACKINFO;
                debug.show();
                send_trackinfo(disc, region);
                debug.phase = phase::RUNNING;
                debug.show();
            }
        }
        if events & reg::EV_SECTOR != 0 && rd(reg::STATUS) & reg::STATUS_STREAMING == 0 {
            let lba = rd(reg::HPS_LBA);
            wr(reg::DEBUG_LBA, lba);
            let failed = if loaded {
                debug.phase = phase::SECTOR;
                debug.show();
                match read_sector(disc, lba) {
                    Ok(()) => false,
                    Err(e) => {
                        debug.error = e as u8;
                        true
                    }
                }
            } else {
                buffer_clear(0, SECTOR_SIZE);
                true
            };
            wr(reg::STREAM, failed as u32);
            debug.phase = phase::RUNNING;
            debug.show();
        }
    }
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    wr(reg::DEBUG, phase::PANIC as u32);
    loop {}
}
