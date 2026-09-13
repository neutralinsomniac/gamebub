//! Driver for the SNES core (`net.gamebub.core.snes.HandheldSnes`).

use std::{
    fs::File,
    io::{Seek, SeekFrom},
    path::PathBuf,
    time::{Duration, Instant},
};

use esp_idf_svc::hal::units::Hertz;
use thiserror::Error;

use crate::{
    core::{
        CoreCartridgeMode, CoreFile, CoreHandler, CoreInfo, CoreSetting, CoreSettingListItem,
        CoreSettingType,
    },
    device::{drivers::fpga, Device},
    ui,
};

use super::{util::color_correction, Bitstream};

pub mod header;

use header::{Chip, RomInfo};

/// The exact SNES master clock, 945/44 MHz (see `HandheldSnes.SysDivider`).
const SYSTEM_CLOCK_RATE: Hertz = Hertz(21_477_272);

/// Largest ROM the core can address (24-bit ROM_ADDR).
const MAX_ROM_SIZE: u32 = 16 * 1024 * 1024;
/// A ROM file may carry a 512-byte copier header in front of the ROM.
const COPIER_HEADER_SIZE: u32 = 512;

// Host windows, see HandheldSnes.scala.
const SDRAM_BASE: u32 = 0x3000_0000;
const SRAM_BASE: u32 = 0x4000_0000;
const COLOR_CORRECTION_BASE: u32 = 0x5000_0000;
/// Transfer rates for the SDRAM (32-bit words) and SRAM (16-bit words) windows, in KB/s.
/// SDRAM writes (ROM, states): the SDRAM runs at 2x the core clock, a host
/// write takes 4 core cycles (186 ns) through the burst CDC, and the
/// controller only refreshes in gaps of 8 SDRAM cycles. 10 MB/s (400 ns per
/// 32-bit word) leaves 214 ns idle per word for them; a faster stream would
/// defer the refreshes and overflow the FPGA's 512-word SPI request FIFO,
/// which is not checked.
const SDRAM_TRANSFER_SPEED: u32 = 10_000;
/// Read the ROM back from the SDRAM after loading and compare it with the
/// file (per transfer chunk), logging the result and the first mismatch.
/// Catches a lost or corrupted upload, and the SDRAM interface returning
/// wrong data, before the game turns them into a black screen or a crash.
/// Costs ~0.3 s per 2.5 MiB of ROM.
const VERIFY_ROM: bool = true;
const SRAM_TRANSFER_SPEED: u32 = 10_000;

// Host registers, see HandheldSnes.scala.
const REG_CONFIG: u32 = 0x0000_0000;
const REG_ROM_TYPE: u32 = 0x0000_0004;
const REG_ROM_MASK: u32 = 0x0000_0008;
const REG_RAM_MASK: u32 = 0x0000_000C;
const REG_RAM_SIZE: u32 = 0x0000_0010;
/// Save-state control: write `SAVE_STATE_SAVE` / `SAVE_STATE_LOAD` with the slot.
const REG_SAVE_STATE: u32 = 0x0000_0014;
const REG_STATUS: u32 = 0x0000_0100;
const REG_STAT_STALLS: u32 = 0x0000_1000;
const REG_STAT_CYCLES: u32 = 0x0000_1004;
const REG_STAT_STALLS_ROM: u32 = 0x0000_1008;
const REG_STAT_STALLS_WRAM: u32 = 0x0000_100C;
const REG_STAT_STALLS_BSRAM: u32 = 0x0000_1010;
const REG_STAT_ROM_MISSES: u32 = 0x0000_1014;
const REG_STAT_ROM_FILL_CYCLES: u32 = 0x0000_1018;
const REG_STAT_ROM_REQUESTS: u32 = 0x0000_101C;
const REG_STAT_ROM_PREFETCHES: u32 = 0x0000_1020;
const REG_STAT_ROM_BUFFER_HITS: u32 = 0x0000_1024;
const REG_STAT_STALLS_FULL: u32 = 0x0000_1028;
const REG_STAT_BSRAM_READS: u32 = 0x0000_102C;
const REG_STAT_BSRAM_WRITES: u32 = 0x0000_1030;
const REG_STAT_BSRAM_BUFFER_HITS: u32 = 0x0000_1034;
const REG_STAT_STALLS_BSRAM_CPU: u32 = 0x0000_1038;
const REG_STAT_STALLS_BSRAM_GSU: u32 = 0x0000_103C;
/// Stall cycles of the worst 2^20-cycle (49 ms) window since the last clear,
/// and that window's ROM / WRAM / BSRAM shares.
const REG_STAT_WORST_STALLS: u32 = 0x0000_1040;
const REG_STAT_WORST_STALLS_ROM: u32 = 0x0000_1044;
const REG_STAT_WORST_STALLS_WRAM: u32 = 0x0000_1048;
const REG_STAT_WORST_STALLS_BSRAM: u32 = 0x0000_104C;
const STAT_WINDOW_CYCLES: u32 = 1 << 20;
/// BSRAM stall cycles by what the BSRAM bridge had at the SRAM: a write, a
/// prefetch, a demand read, nothing.
const REG_STAT_STALLS_BSRAM_WRITE: u32 = 0x0000_1050;
const REG_STAT_STALLS_BSRAM_PREFETCH: u32 = 0x0000_1054;
const REG_STAT_STALLS_BSRAM_DEMAND: u32 = 0x0000_1058;
const REG_STAT_STALLS_BSRAM_IDLE: u32 = 0x0000_105C;
/// BSRAM stall cycles while the WRAM bridge had an access at the SRAM.
const REG_STAT_STALLS_BSRAM_WRAM: u32 = 0x0000_1060;
/// BSRAM misses whose next coprocessor sample came 1 / 2 / 3 / 4+ core cycles after the strobe.
const REG_STAT_SAMPLE_AGE: [u32; 4] = [0x0000_1064, 0x0000_1068, 0x0000_106C, 0x0000_1070];
/// BSRAM misses whose cache entry held another word (conflict misses).
const REG_STAT_BSRAM_CONFLICTS: u32 = 0x0000_1074;

const CONFIG_PAL: u32 = 1 << 0;
const CONFIG_BLEND: u32 = 1 << 1;
const CONFIG_LATENCY_HIDING: u32 = 1 << 2;
const CONFIG_GSU_RAM_LATENCY_HIDING: u32 = 1 << 7;
/// Unsafe (see HandheldSnes); left clear.
#[allow(dead_code)]
const CONFIG_GSU_ROM_LATENCY_HIDING: u32 = 1 << 8;
const CONFIG_SA1_ROM_LATENCY_HIDING: u32 = 1 << 9;
const CONFIG_SA1_BWRAM_LATENCY_HIDING: u32 = 1 << 10;
/// ROM miss path: answer with the word as it arrives / issue the first word
/// in the cache's lookup cycle. Separately switchable for A/B testing (the
/// "ROM Miss Path" setting).
const CONFIG_ROM_EARLY_ANSWERS: u32 = 1 << 11;
const CONFIG_ROM_EAGER_ISSUE: u32 = 1 << 12;
const CONFIG_ROM_PATH: u32 = CONFIG_ROM_EARLY_ANSWERS | CONFIG_ROM_EAGER_ISSUE;
/// Debug A/B switches for the BSRAM cache: off entirely / way 0 only (a
/// direct-mapped cache of half the size). Normally clear (the "BSRAM Cache"
/// setting).
const CONFIG_BSRAM_CACHE_OFF: u32 = 1 << 13;
const CONFIG_BSRAM_ONE_WAY: u32 = 1 << 14;

/// Non-power-of-two ROMs are mirrored up to their padded size as they load;
/// the mirror map is computed at this granularity (the ROM size must be a
/// multiple of it, or of 4 bytes at the least) and kept as a list of
/// linear segments, which is short for real cartridge sizes.
const MIRROR_GRANULE: u32 = 512;
const MIRROR_SEGMENTS_MAX: usize = 1024;

const SAVE_STATE_SAVE: u32 = 1 << 0;
const SAVE_STATE_LOAD: u32 = 1 << 1;
const SAVE_STATE_SLOT_SHIFT: u32 = 2;
/// Status bits (REG_STATUS).
const STATUS_SS_AVAIL: u32 = 1 << 6;
const STATUS_SS_SAVE_DONE: u32 = 1 << 8;
/// A save / load is in progress: the core runs without focus until the
/// program has finished (or the request is cancelled).
const STATUS_SS_ACTIVE: u32 = 1 << 10;

// SDRAM layout (byte addresses), see HandheldSnes.SdramMap.
/// The MiSTer save-state program, fetched by the core at ROM address 0xFF0000.
const SDRAM_SAVE_STATE_PROGRAM: u32 = 0xFF_0000;
/// Save-state slots, 4 x 1 MiB, above the ROM.
const SDRAM_SAVE_STATE_BASE: u32 = 0x100_0000;
const SDRAM_SAVE_STATE_SLOT_SIZE: u32 = 0x10_0000;
const SAVE_STATE_SLOTS: u32 = 4;
const SDRAM_SAVE_STATE_SIZE: u32 = SAVE_STATE_SLOTS * SDRAM_SAVE_STATE_SLOT_SIZE;
/// The 65816 save-state program (upstream MiSTer's `boot1.rom`; see fpga/verilog/snes/README.md).
static SAVE_STATE_PROGRAM: &[u8] =
    include_bytes!("../../../../../fpga/verilog/snes/savestates/savestates.bin");
/// The program writes this at byte 8 of a state; the core checks it before loading.
const SAVE_STATE_MAGIC: &[u8; 4] = b"SNES";
/// How long to let the game run for it to reach an NMI / IRQ and dump its state.
const SAVE_STATE_TIMEOUT: Duration = Duration::from_secs(5);

// SRAM layout (byte addresses), see HandheldSnes.SramMap.
const SRAM_BSRAM_BASE: u32 = 0x0_0000;
const SRAM_BSRAM_SIZE: u32 = 256 * 1024;
const SRAM_WRAM_BASE: u32 = 0x4_0000;
const SRAM_WRAM_SIZE: u32 = 128 * 1024;

/// The core's files (see `get_core_info`).
const FILE_ROM: u16 = 0;
const FILE_SAVE: u16 = 1;
/// The save-state slots (one file, whole slots up to the last one in use).
const FILE_STATES: u16 = 2;

/// The core's settings (see `get_core_info`).
const SETTING_RESET: u16 = 0;
const SETTING_REGION: u16 = 1;
const SETTING_BLEND: u16 = 2;
const SETTING_LATENCY_HIDING: u16 = 3;
const SETTING_ROM_PATH: u16 = 4;
const SETTING_BSRAM_CACHE: u16 = 5;
const SETTING_SAVE_STATE: u16 = 6;
const SETTING_LOAD_STATE: u16 = 7;
const SETTING_STATE_SLOT: u16 = 8;
/// Settings handled by the driver rather than written to a register.
const SETTING_ADDRESS_NONE: u32 = 0xFFFF_FFFF;
/// "Region" values.
const REGION_AUTO: u32 = 0;
const REGION_NTSC: u32 = 1;
const REGION_PAL: u32 = 2;

/// The values of the settings that go into the config register.
struct Settings {
    region: u32,
    blend: bool,
    latency_hiding: bool,
    /// `CONFIG_ROM_*` bits.
    rom_path: u32,
    /// `CONFIG_BSRAM_*` bits.
    bsram_cache: u32,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            region: REGION_AUTO,
            blend: false,
            latency_hiding: true,
            rom_path: CONFIG_ROM_PATH,
            bsram_cache: 0,
        }
    }
}

/// A run of the ROM mirrored (as it loads) to `dest`.
struct MirrorSegment {
    dest: u32,
    src: u32,
    len: u32,
}

#[derive(Debug, Error)]
pub enum SnesError {
    #[error("I/O error")]
    IoError(#[from] std::io::Error),
    #[error("FPGA error")]
    FpgaError(#[from] crate::device::drivers::fpga::Error),
    #[error("{0}")]
    Unsupported(String),
}

/// Driver for the SNES core.
pub struct Snes {
    /// The loaded ROM's header analysis.
    rom_info: Option<RomInfo>,
    /// Bytes of the ROM the core manager has transferred to the SDRAM.
    rom_loaded: u32,
    /// A checksum of each chunk of the ROM as transferred (`VERIFY_ROM`).
    rom_chunks: Vec<(u32, u32)>,
    /// Where the ROM's mirrors go (empty for a power-of-two ROM).
    mirror_segments: Vec<MirrorSegment>,
    /// A mirror write failed while the ROM loaded.
    mirror_failed: bool,
    /// The current settings values.
    settings: Settings,
    /// Bytes of the save file the core manager has transferred to the SRAM
    /// (None: no save file, the region was cleared).
    save_loaded: Option<u32>,
    /// Size of the backup RAM in bytes (0: none).
    save_size: u32,
    /// Whether save states are possible for the loaded ROM (the program's
    /// ROM area is free).
    save_states: bool,
    /// Bytes of the states file the core manager has transferred to the SDRAM.
    states_loaded: u32,
    /// The slots holding a state (loaded from the states file, or saved since).
    state_valid: [bool; SAVE_STATE_SLOTS as usize],
    /// The selected slot, 0-based (the "State Slot" setting).
    state_slot: u32,
}

impl Snes {
    pub fn new() -> Self {
        Snes {
            rom_info: None,
            rom_loaded: 0,
            rom_chunks: Vec::new(),
            mirror_segments: Vec::new(),
            mirror_failed: false,
            settings: Settings::default(),
            save_loaded: None,
            save_size: 0,
            save_states: false,
            states_loaded: 0,
            state_valid: [false; SAVE_STATE_SLOTS as usize],
            state_slot: 0,
        }
    }

    pub fn get_core_info() -> CoreInfo {
        CoreInfo {
            id: "Game-Bub.SNES".try_into().unwrap(),
            name: "Super Nintendo".try_into().unwrap(),
            author: "Game Bub".try_into().unwrap(),
            is_built_in: true,
            core_dir: PathBuf::new(),
            uses_cartridge: CoreCartridgeMode::No,
            files: [
                CoreFile {
                    id: FILE_ROM,
                    label: "ROM".try_into().unwrap(),
                    extensions: ["sfc".try_into().unwrap(), "smc".try_into().unwrap()]
                        .into_iter()
                        .collect(),
                    filename: None,

                    optional: false,
                    read_only: true,
                    user_selected: true,
                    dependent_on_0: false,
                    initialize: false,

                    address: SDRAM_BASE,
                    max_size: MAX_ROM_SIZE + COPIER_HEADER_SIZE,
                    exact_size: 0,
                    max_transfer_speed: SDRAM_TRANSFER_SPEED,
                    transfer_word_size: fpga::FpgaSpiWordSize::Bits32,
                },
                CoreFile {
                    id: FILE_SAVE,
                    label: "Save".try_into().unwrap(),
                    extensions: ["srm".try_into().unwrap()].into_iter().collect(),
                    filename: None,

                    optional: true,
                    read_only: false,
                    user_selected: false,
                    dependent_on_0: true,
                    initialize: true,

                    address: SRAM_BASE + SRAM_BSRAM_BASE,
                    max_size: SRAM_BSRAM_SIZE,
                    exact_size: 0,
                    max_transfer_speed: SRAM_TRANSFER_SPEED,
                    transfer_word_size: fpga::FpgaSpiWordSize::Bits16,
                },
                CoreFile {
                    id: FILE_STATES,
                    label: "States".try_into().unwrap(),
                    extensions: ["ss".try_into().unwrap()].into_iter().collect(),
                    filename: None,

                    optional: true,
                    read_only: false,
                    user_selected: false,
                    dependent_on_0: true,
                    // Cleared when there is no file, so that stale slots
                    // (another game's states) are not taken for valid ones.
                    initialize: true,

                    address: SDRAM_BASE + SDRAM_SAVE_STATE_BASE,
                    max_size: SDRAM_SAVE_STATE_SIZE,
                    exact_size: 0,
                    max_transfer_speed: SDRAM_TRANSFER_SPEED,
                    transfer_word_size: fpga::FpgaSpiWordSize::Bits32,
                },
            ]
            .into_iter()
            .collect(),
            settings: [
                CoreSetting {
                    id: SETTING_RESET,
                    label: "Reset Core".into(),
                    // Register 0x2000: a one-cycle core reset (see HandheldSnes.scala).
                    address: 0x0000_2000,
                    mask: 0,
                    default: 0,
                    inner: CoreSettingType::Action { value: 1 },
                },
                CoreSetting {
                    id: SETTING_SAVE_STATE,
                    label: "Save State".into(),
                    address: SETTING_ADDRESS_NONE,
                    mask: 0,
                    default: 0,
                    inner: CoreSettingType::Action { value: 1 },
                },
                CoreSetting {
                    id: SETTING_LOAD_STATE,
                    label: "Load State".into(),
                    address: SETTING_ADDRESS_NONE,
                    mask: 0,
                    default: 0,
                    inner: CoreSettingType::Action { value: 1 },
                },
                CoreSetting {
                    id: SETTING_STATE_SLOT,
                    label: "State Slot".into(),
                    address: SETTING_ADDRESS_NONE,
                    mask: 0,
                    default: 0,
                    inner: CoreSettingType::List {
                        items: (0..SAVE_STATE_SLOTS)
                            .map(|slot| CoreSettingListItem {
                                label: format!("{}", slot + 1).into(),
                                value: slot,
                            })
                            .collect(),
                    },
                },
                CoreSetting {
                    id: SETTING_REGION,
                    label: "Region".into(),
                    address: SETTING_ADDRESS_NONE,
                    mask: 0,
                    default: REGION_AUTO,
                    inner: CoreSettingType::List {
                        items: [
                            ("Auto", REGION_AUTO),
                            ("NTSC", REGION_NTSC),
                            ("PAL", REGION_PAL),
                        ]
                        .into_iter()
                        .map(|(label, value)| CoreSettingListItem {
                            label: label.into(),
                            value,
                        })
                        .collect(),
                    },
                },
                CoreSetting {
                    id: SETTING_BLEND,
                    label: "Pseudo Transparency".into(),
                    address: SETTING_ADDRESS_NONE,
                    mask: 0,
                    default: 0,
                    inner: CoreSettingType::Checkbox { value: 1 },
                },
                CoreSetting {
                    id: SETTING_LATENCY_HIDING,
                    label: "Memory Latency Hiding".into(),
                    address: SETTING_ADDRESS_NONE,
                    mask: 0,
                    default: 1,
                    inner: CoreSettingType::Checkbox { value: 1 },
                },
                CoreSetting {
                    id: SETTING_ROM_PATH,
                    label: "Debug: ROM Miss Path".into(),
                    address: SETTING_ADDRESS_NONE,
                    mask: 0,
                    default: CONFIG_ROM_PATH,
                    inner: CoreSettingType::List {
                        items: [
                            ("Early answers + eager issue", CONFIG_ROM_PATH),
                            ("Early answers", CONFIG_ROM_EARLY_ANSWERS),
                            ("Eager issue", CONFIG_ROM_EAGER_ISSUE),
                            ("Neither", 0),
                        ]
                        .into_iter()
                        .map(|(label, value)| CoreSettingListItem {
                            label: label.into(),
                            value,
                        })
                        .collect(),
                    },
                },
                CoreSetting {
                    id: SETTING_BSRAM_CACHE,
                    label: "Debug: BSRAM Cache".into(),
                    address: SETTING_ADDRESS_NONE,
                    mask: 0,
                    default: 0,
                    inner: CoreSettingType::List {
                        items: [
                            ("2-way", 0),
                            ("1-way", CONFIG_BSRAM_ONE_WAY),
                            ("Off", CONFIG_BSRAM_CACHE_OFF),
                        ]
                        .into_iter()
                        .map(|(label, value)| CoreSettingListItem {
                            label: label.into(),
                            value,
                        })
                        .collect(),
                    },
                },
            ]
            .into_iter()
            .collect(),
            bitstream: crate::util::get_system_file_path("snes.bit.hs"),
        }
    }

    /// Write to the SDRAM window (32-bit words).
    fn sdram_write(device: &mut Device, address: u32, data: &[u8]) -> Result<(), fpga::Error> {
        device.fpga.spi_write(
            Some(Hertz(SDRAM_TRANSFER_SPEED * 1000 * 2)),
            fpga::SpiCommand::new(fpga::FpgaSpiWordSize::Bits32),
            SDRAM_BASE + address,
            data,
        )
    }

    /// Read from the SDRAM window (32-bit words).
    /// FNV-1a over the bytes (a mismatch check, nothing more).
    fn checksum(data: &[u8]) -> u32 {
        data.iter().fold(0x811C_9DC5u32, |h, &b| (h ^ b as u32).wrapping_mul(0x0100_0193))
    }

    /// Read the ROM back from the SDRAM and compare it with the chunks as
    /// transferred (`VERIFY_ROM`); logs the result.
    fn verify_rom(&self, scratch: &mut [u8]) {
        let start = std::time::Instant::now();
        let mut offset = 0u32;
        let mut mismatches = 0u32;
        let mut first: Option<u32> = None;
        for &(len, sum) in &self.rom_chunks {
            let mut pos = 0u32;
            let mut got = 0x811C_9DC5u32;
            while pos < len {
                let n = ((len - pos) as usize).min(scratch.len());
                if let Err(e) = Self::sdram_read(&mut Device::lock(), offset + pos, &mut scratch[..n]) {
                    log::error!("ROM read-back failed at {:#x}: {}", offset + pos, e);
                    return;
                }
                got = scratch[..n].iter().fold(got, |h, &b| (h ^ b as u32).wrapping_mul(0x0100_0193));
                pos += n as u32;
            }
            if got != sum {
                mismatches += 1;
                first.get_or_insert(offset);
            }
            offset += len;
        }
        match first {
            None => log::info!(
                "ROM read-back OK: {} chunks, {} bytes in {} ms",
                self.rom_chunks.len(),
                offset,
                start.elapsed().as_millis()
            ),
            Some(at) => log::error!(
                "ROM READ-BACK MISMATCH: {} of {} chunks differ, first at {:#x} (SDRAM upload or interface)",
                mismatches,
                self.rom_chunks.len(),
                at
            ),
        }
    }

    fn sdram_read(device: &mut Device, address: u32, data: &mut [u8]) -> Result<(), fpga::Error> {
        device.fpga.spi_read(
            Some(fpga::MAX_SPI_READ_CLOCK),
            fpga::SpiCommand::new(fpga::FpgaSpiWordSize::Bits32),
            SDRAM_BASE + address,
            data,
        )
    }

    /// Write to the SRAM window (16-bit words).
    fn sram_write(device: &mut Device, address: u32, data: &[u8]) -> Result<(), fpga::Error> {
        device.fpga.spi_write(
            Some(Hertz(SRAM_TRANSFER_SPEED * 1000 * 2)),
            fpga::SpiCommand::new(fpga::FpgaSpiWordSize::Bits16),
            SRAM_BASE + address,
            data,
        )
    }

    /// Apply the settings that don't depend on the ROM.
    fn initialize(&mut self, device: &mut Device) -> Result<(), SnesError> {
        device.fpga.disable_interrupt(fpga::Irq::ModuleVblank)?;
        // The SNES outputs 15-bit RGB; no color correction for now.
        color_correction::presets::IDENTITY.configure(device, COLOR_CORRECTION_BASE)?;
        Ok(())
    }

    fn check_supported(info: &RomInfo) -> Result<(), SnesError> {
        // Keep in sync with the coprocessors enabled in `snes.SnesCoreConfig`.
        let unsupported = match info.chip() {
            Chip::None | Chip::Sdd1 => None,
            Chip::Dsp1 | Chip::Dsp2 | Chip::Dsp3 | Chip::Dsp4 | Chip::Obc1 => None,
            Chip::Cx4 | Chip::Sa1 | Chip::Gsu => None,
            Chip::Spc7110 => Some("SPC7110"),
            Chip::Bsx => Some("Satellaview"),
            Chip::Sufami => Some("Sufami Turbo"),
            Chip::Cc92 | Chip::Pf94 => Some("competition cartridge"),
            Chip::Unknown(_) => Some("unknown mapper"),
        };
        if let Some(name) = unsupported {
            return Err(SnesError::Unsupported(format!(
                "{} cartridges are not supported yet",
                name
            )));
        }
        if info.padded_rom_size() > MAX_ROM_SIZE {
            return Err(SnesError::Unsupported("ROM is larger than 16 MiB".into()));
        }
        Ok(())
    }

    /// Fill a region of the FPGA SRAM using `pattern(byte_offset)`.
    fn fill_sram(
        device: &mut Device,
        scratch: &mut [u8],
        base: u32,
        size: u32,
        pattern: impl Fn(u32) -> u8,
    ) -> Result<(), SnesError> {
        let mut pos = 0u32;
        while pos < size {
            let n = ((size - pos) as usize).min(scratch.len());
            for (i, b) in scratch[..n].iter_mut().enumerate() {
                *b = pattern(pos + i as u32);
            }
            Self::sram_write(device, base + pos, &scratch[..n])?;
            pos += n as u32;
        }
        Ok(())
    }

    /// Analyze the ROM header and position the file at the ROM data (past
    /// any copier header), for the core manager to transfer to the SDRAM.
    fn prepare_rom(&mut self, file: &mut File) -> Result<(), SnesError> {
        let file_size = file.metadata()?.len();
        let info = header::analyze(file, file_size)?;
        log::info!(
            "Loading SNES rom: '{}' type={:#04x} ({:?}) rom={}K (padded {}K) ram={}K pal={} header@{:?}",
            info.title_str(),
            info.rom_type,
            info.chip(),
            info.rom_size / 1024,
            info.padded_rom_size() / 1024,
            info.ram_size() / 1024,
            info.pal,
            info.header_offset,
        );
        if info.header_offset.is_none() {
            log::warn!("No SNES header found, assuming LoROM");
        }
        Self::check_supported(&info)?;
        file.seek(SeekFrom::Start(info.data_offset))?;

        self.save_size = info.ram_size().min(SRAM_BSRAM_SIZE);
        self.mirror_segments = Self::mirror_segments(info.rom_size, info.padded_rom_size())?;
        self.mirror_failed = false;
        // The save-state program lives above the ROM, in ROM address space.
        self.save_states = info.padded_rom_size() <= SDRAM_SAVE_STATE_PROGRAM;
        self.rom_info = Some(info);
        self.rom_loaded = 0;
        self.rom_chunks.clear();
        Ok(())
    }

    /// Where a non-power-of-two ROM is mirrored up to its padded size, the
    /// way a real cartridge's partially-decoded address lines would: runs of
    /// `MIRROR_GRANULE` blocks (`header::mirror_address` is linear within an
    /// aligned block when the ROM size is a multiple of the block size).
    fn mirror_segments(rom_size: u32, padded_size: u32) -> Result<Vec<MirrorSegment>, SnesError> {
        let mut segments: Vec<MirrorSegment> = Vec::new();
        if rom_size == 0 || rom_size >= padded_size {
            return Ok(segments);
        }
        // The largest power of two dividing the ROM size, up to the granule.
        let granule = (rom_size & rom_size.wrapping_neg()).min(MIRROR_GRANULE);
        if granule < 4 {
            return Err(SnesError::Unsupported(
                "the ROM size is not a multiple of 4 bytes".into(),
            ));
        }
        let mut dest = rom_size;
        while dest < padded_size {
            let src = header::mirror_address(dest, rom_size);
            match segments.last_mut() {
                Some(last) if last.dest + last.len == dest && last.src + last.len == src => {
                    last.len += granule;
                }
                _ => {
                    if segments.len() >= MIRROR_SEGMENTS_MAX {
                        return Err(SnesError::Unsupported("unsupported ROM size".into()));
                    }
                    segments.push(MirrorSegment {
                        dest,
                        src,
                        len: granule,
                    });
                }
            }
            dest += granule;
        }
        Ok(segments)
    }

    /// Write the mirrors of the ROM chunk at `offset` as the core manager
    /// transfers it (the chunk is written to the SDRAM already).
    fn mirror_chunk(&self, offset: u32, data: &[u8]) -> Result<(), SnesError> {
        let end = offset + data.len() as u32;
        for segment in &self.mirror_segments {
            let lo = segment.src.max(offset);
            let hi = (segment.src + segment.len).min(end);
            if lo < hi {
                Self::sdram_write(
                    &mut Device::lock(),
                    segment.dest + (lo - segment.src),
                    &data[(lo - offset) as usize..(hi - offset) as usize],
                )?;
            }
        }
        Ok(())
    }

    /// The config register for the loaded ROM and the current settings.
    fn config_value(&self, info: &RomInfo) -> u32 {
        let settings = &self.settings;
        let mut config = settings.rom_path | settings.bsram_cache;
        if settings.latency_hiding {
            // Latency hiding only stalls the core when the CPU is about to
            // latch data, so it is unsafe for coprocessors that fetch ROM /
            // RAM on their own schedule. The Super FX exports its RAM
            // sampling instants, so its RAM reads are hidden (its ROM reads
            // are not, see HandheldSnes); the SA-1 exports its ROM sampling
            // instants, so its ROM reads are hidden; CX4 gets the
            // conservative mode.
            config |= match info.chip() {
                Chip::Gsu => CONFIG_GSU_RAM_LATENCY_HIDING,
                Chip::Sa1 => CONFIG_SA1_ROM_LATENCY_HIDING | CONFIG_SA1_BWRAM_LATENCY_HIDING,
                Chip::Cx4 => 0,
                _ => CONFIG_LATENCY_HIDING,
            };
        }
        if settings.blend {
            config |= CONFIG_BLEND;
        }
        let pal = match settings.region {
            REGION_NTSC => false,
            REGION_PAL => true,
            _ => info.pal,
        };
        if pal {
            config |= CONFIG_PAL;
        }
        config
    }

    /// Set up the memories and the core for the loaded ROM.
    fn prepare_run(&mut self) -> Result<(), SnesError> {
        let info = self
            .rom_info
            .clone()
            .ok_or_else(|| SnesError::Unsupported("no ROM loaded".into()))?;
        let mut scratch = super::SCRATCH.take().expect("scratch buffer");

        if self.rom_loaded < info.rom_size {
            return Err(SnesError::Unsupported(format!(
                "the ROM file is truncated ({} of {} bytes)",
                self.rom_loaded, info.rom_size
            )));
        }
        if self.mirror_failed {
            return Err(SnesError::Unsupported("mirroring the ROM failed".into()));
        }
        self.initialize(&mut Device::lock())?;
        if VERIFY_ROM {
            self.verify_rom(&mut scratch);
        }
        if self.save_states {
            Self::sdram_write(
                &mut Device::lock(),
                SDRAM_SAVE_STATE_PROGRAM,
                SAVE_STATE_PROGRAM,
            )?;
        }
        self.check_state_slots()?;

        // Initialize WRAM with the same pattern the MiSTer core uses
        // (some games rely on non-zero power-on RAM contents).
        Self::fill_sram(
            &mut Device::lock(),
            &mut scratch,
            SRAM_WRAM_BASE,
            SRAM_WRAM_SIZE,
            |a| {
                if ((a >> 8) ^ (a >> 2)) & 1 != 0 {
                    0x66
                } else {
                    0x99
                }
            },
        )?;

        // A save file shorter than the backup RAM leaves the rest to clear
        // (a missing one was cleared entirely by the core manager).
        if let Some(loaded) = self.save_loaded {
            if loaded < self.save_size {
                Self::fill_sram(
                    &mut Device::lock(),
                    &mut scratch,
                    SRAM_BSRAM_BASE + loaded,
                    self.save_size - loaded,
                    |_| 0xFF,
                )?;
            }
        }
        drop(scratch);

        // Configure the core.
        let mut device = Device::lock();
        device.fpga.write_u32(REG_CONFIG, self.config_value(&info))?;
        device.fpga.write_u32(REG_ROM_TYPE, info.rom_type as u32)?;
        device.fpga.write_u32(REG_ROM_MASK, info.rom_mask())?;
        device.fpga.write_u32(REG_RAM_MASK, info.ram_mask())?;
        device.fpga.write_u32(REG_RAM_SIZE, info.ram_size_code as u32)?;
        device.fpga.write_u32(REG_STAT_CYCLES, 0)?;
        device.fpga.write_u32(REG_STAT_STALLS, 0)?;
        Ok(())
    }

    /// The SDRAM address of a save-state slot.
    fn state_slot_base(slot: u32) -> u32 {
        SDRAM_SAVE_STATE_BASE + slot * SDRAM_SAVE_STATE_SLOT_SIZE
    }

    /// Whether a slot in the SDRAM holds a state: the magic at byte 8 and a
    /// sane size (bits 49:32 of the first word, in 32-bit words).
    fn state_slot_valid(device: &mut Device, slot: u32) -> Result<bool, SnesError> {
        let mut header = [0u8; 16];
        Self::sdram_read(device, Self::state_slot_base(slot), &mut header)?;
        let size_words = u32::from_le_bytes(header[4..8].try_into().unwrap()) & 0x3FFFF;
        Ok(&header[8..12] == SAVE_STATE_MAGIC
            && size_words >= 4
            && size_words << 2 <= SDRAM_SAVE_STATE_SLOT_SIZE)
    }

    /// Find the slots the states file filled (once it is loaded).
    fn check_state_slots(&mut self) -> Result<(), SnesError> {
        let mut device = Device::lock();
        for slot in 0..SAVE_STATE_SLOTS {
            let loaded = self.states_loaded >= (slot + 1) * SDRAM_SAVE_STATE_SLOT_SIZE;
            self.state_valid[slot as usize] =
                loaded && Self::state_slot_valid(&mut device, slot)?;
        }
        Ok(())
    }

    /// Check that save states are possible for the loaded ROM and cartridge type.
    fn check_save_states(&self, device: &mut Device) -> Result<(), SnesError> {
        if !self.save_states {
            return Err(SnesError::Unsupported(
                "save states are not available for this ROM".into(),
            ));
        }
        if device.fpga.read_u32(REG_STATUS)? & STATUS_SS_AVAIL == 0 {
            return Err(SnesError::Unsupported(
                "save states are not supported for this cartridge type".into(),
            ));
        }
        Ok(())
    }

    /// Request a save or a load and wait for the core to carry it out. The
    /// request comes from the settings menu, so the game is paused: the glue
    /// runs the core without focus (no input, no sound) until the game
    /// reaches an interrupt and the program has run. A game that waits with
    /// interrupts off never gets there: give up and cancel the request.
    fn run_save_state_request(request: u32) -> Result<(), SnesError> {
        Device::lock().fpga.write_u32(REG_SAVE_STATE, request)?;
        let start = Instant::now();
        loop {
            let status = Device::lock().fpga.read_u32(REG_STATUS)?;
            if status & STATUS_SS_ACTIVE == 0 {
                log::info!(
                    "Save-state program finished in {} ms",
                    start.elapsed().as_millis()
                );
                return Ok(());
            }
            if start.elapsed() > SAVE_STATE_TIMEOUT {
                Device::lock().fpga.write_u32(REG_SAVE_STATE, 0)?;
                return Err(SnesError::Unsupported(
                    "the game did not reach an interrupt in time".into(),
                ));
            }
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    /// Save the machine state to `slot` in the SDRAM (the core manager
    /// writes the states file when the core exits).
    fn save_state(&mut self, slot: u32) -> Result<(), SnesError> {
        self.check_save_states(&mut Device::lock())?;
        Self::run_save_state_request(SAVE_STATE_SAVE | (slot << SAVE_STATE_SLOT_SHIFT))?;
        let mut device = Device::lock();
        if device.fpga.read_u32(REG_STATUS)? & STATUS_SS_SAVE_DONE == 0
            || !Self::state_slot_valid(&mut device, slot)?
        {
            return Err(SnesError::Unsupported(
                "the core did not write the state".into(),
            ));
        }
        self.state_valid[slot as usize] = true;
        Ok(())
    }

    /// Restore the machine state of `slot`.
    fn load_state(&mut self, slot: u32) -> Result<(), SnesError> {
        self.check_save_states(&mut Device::lock())?;
        if !self.state_valid[slot as usize] {
            return Err(SnesError::Unsupported(format!(
                "slot {} is empty",
                slot + 1
            )));
        }
        Self::run_save_state_request(SAVE_STATE_LOAD | (slot << SAVE_STATE_SLOT_SHIFT))
    }

    /// Report the outcome of a save-state operation to the user.
    fn notify_state_result(result: Result<(), SnesError>, what: &str, slot: u32) {
        let text = match result {
            Ok(()) => format!("State {} slot {}", what, slot + 1),
            Err(err) => {
                log::error!("Save state failed: {}", err);
                format!("Save state failed: {}", err)
            }
        };
        ui::send(ui::Message::Notification(ui::Notification::new_short(text)));
    }

    /// Log and clear the stall statistics.
    fn log_stats(device: &mut Device) -> Result<(), fpga::Error> {
        let num_cycles = device.fpga.read_u32(REG_STAT_CYCLES)?;
        let num_stalls = device.fpga.read_u32(REG_STAT_STALLS)?;
        let stalls_rom = device.fpga.read_u32(REG_STAT_STALLS_ROM)?;
        let stalls_wram = device.fpga.read_u32(REG_STAT_STALLS_WRAM)?;
        let stalls_bsram = device.fpga.read_u32(REG_STAT_STALLS_BSRAM)?;
        let rom_misses = device.fpga.read_u32(REG_STAT_ROM_MISSES)?;
        let rom_fill_cycles = device.fpga.read_u32(REG_STAT_ROM_FILL_CYCLES)?;
        let rom_requests = device.fpga.read_u32(REG_STAT_ROM_REQUESTS)?;
        let rom_prefetches = device.fpga.read_u32(REG_STAT_ROM_PREFETCHES)?;
        let rom_buffer_hits = device.fpga.read_u32(REG_STAT_ROM_BUFFER_HITS)?;
        let stalls_full = device.fpga.read_u32(REG_STAT_STALLS_FULL)?;
        let bsram_reads = device.fpga.read_u32(REG_STAT_BSRAM_READS)?;
        let bsram_writes = device.fpga.read_u32(REG_STAT_BSRAM_WRITES)?;
        let bsram_buffer_hits = device.fpga.read_u32(REG_STAT_BSRAM_BUFFER_HITS)?;
        let stalls_bsram_cpu = device.fpga.read_u32(REG_STAT_STALLS_BSRAM_CPU)?;
        let stalls_bsram_gsu = device.fpga.read_u32(REG_STAT_STALLS_BSRAM_GSU)?;
        let worst_stalls = device.fpga.read_u32(REG_STAT_WORST_STALLS)?;
        let worst_stalls_rom = device.fpga.read_u32(REG_STAT_WORST_STALLS_ROM)?;
        let worst_stalls_wram = device.fpga.read_u32(REG_STAT_WORST_STALLS_WRAM)?;
        let worst_stalls_bsram = device.fpga.read_u32(REG_STAT_WORST_STALLS_BSRAM)?;
        let stalls_bsram_write = device.fpga.read_u32(REG_STAT_STALLS_BSRAM_WRITE)?;
        let stalls_bsram_prefetch = device.fpga.read_u32(REG_STAT_STALLS_BSRAM_PREFETCH)?;
        let stalls_bsram_demand = device.fpga.read_u32(REG_STAT_STALLS_BSRAM_DEMAND)?;
        let stalls_bsram_idle = device.fpga.read_u32(REG_STAT_STALLS_BSRAM_IDLE)?;
        let stalls_bsram_wram = device.fpga.read_u32(REG_STAT_STALLS_BSRAM_WRAM)?;
        let bsram_conflicts = device.fpga.read_u32(REG_STAT_BSRAM_CONFLICTS)?;
        let mut sample_age = [0u32; 4];
        for (i, reg) in REG_STAT_SAMPLE_AGE.iter().enumerate() {
            sample_age[i] = device.fpga.read_u32(*reg)?;
            device.fpga.write_u32(*reg, 0)?;
        }
        for reg in [
            REG_STAT_CYCLES,
            REG_STAT_STALLS,
            REG_STAT_STALLS_ROM,
            REG_STAT_STALLS_WRAM,
            REG_STAT_STALLS_BSRAM,
            REG_STAT_ROM_MISSES,
            REG_STAT_ROM_FILL_CYCLES,
            REG_STAT_ROM_REQUESTS,
            REG_STAT_ROM_PREFETCHES,
            REG_STAT_ROM_BUFFER_HITS,
            REG_STAT_STALLS_FULL,
            REG_STAT_BSRAM_READS,
            REG_STAT_BSRAM_WRITES,
            REG_STAT_BSRAM_BUFFER_HITS,
            REG_STAT_STALLS_BSRAM_CPU,
            REG_STAT_STALLS_BSRAM_GSU,
            REG_STAT_WORST_STALLS,
            REG_STAT_WORST_STALLS_ROM,
            REG_STAT_WORST_STALLS_WRAM,
            REG_STAT_WORST_STALLS_BSRAM,
            REG_STAT_STALLS_BSRAM_WRITE,
            REG_STAT_STALLS_BSRAM_PREFETCH,
            REG_STAT_STALLS_BSRAM_DEMAND,
            REG_STAT_STALLS_BSRAM_IDLE,
            REG_STAT_STALLS_BSRAM_WRAM,
            REG_STAT_BSRAM_CONFLICTS,
        ] {
            device.fpga.write_u32(reg, 0)?;
        }
        let rate = (num_cycles as f32) / ((num_cycles as f32) + (num_stalls as f32));
        log::info!(
            "Run rate: {}% (cycles={} stalls={}: rom={} wram={} bsram={} queue full={}; rom requests={} buffer hits={} misses={} prefetches={} fill cycles={}; bsram reads={} buffer hits={} writes={} stalls at cpu={} coprocessor={})",
            rate * 100.0,
            num_cycles,
            num_stalls,
            stalls_rom,
            stalls_wram,
            stalls_bsram,
            stalls_full,
            rom_requests,
            rom_buffer_hits,
            rom_misses,
            rom_prefetches,
            rom_fill_cycles,
            bsram_reads,
            bsram_buffer_hits,
            bsram_writes,
            stalls_bsram_cpu,
            stalls_bsram_gsu
        );
        log::info!(
            "BSRAM stalls while the SRAM had: write={} prefetch={} demand read={} nothing={}; wram busy={}",
            stalls_bsram_write,
            stalls_bsram_prefetch,
            stalls_bsram_demand,
            stalls_bsram_idle,
            stalls_bsram_wram
        );
        log::info!(
            "BSRAM misses sampled 1/2/3/4+ cycles after the strobe: {} {} {} {}; conflict misses={}",
            sample_age[0],
            sample_age[1],
            sample_age[2],
            sample_age[3],
            bsram_conflicts
        );
        // The totals average brief slowdowns away; the worst window shows them.
        log::info!(
            "Worst 49 ms window: {}% (stalls={}: rom={} wram={} bsram={})",
            (worst_stalls as f32) / (STAT_WINDOW_CYCLES as f32) * 100.0,
            worst_stalls,
            worst_stalls_rom,
            worst_stalls_wram,
            worst_stalls_bsram
        );
        Ok(())
    }
}

impl Bitstream for Snes {
    fn on_vblank_irq(&mut self) {}
}

impl CoreHandler for Snes {
    fn as_legacy_bitstream(&mut self) -> &mut dyn Bitstream {
        self
    }

    fn on_after_program(&mut self) {
        Device::lock().fpga.set_system_clock_rate(SYSTEM_CLOCK_RATE);
    }

    fn get_file_path_override(&mut self, _id: u16) -> Option<PathBuf> {
        None
    }

    fn on_before_file_load(&mut self, id: u16, file: &mut File) -> Result<(), String> {
        match id {
            FILE_ROM => self.prepare_rom(file).map_err(|e| e.to_string()),
            FILE_SAVE => {
                self.save_loaded = Some(0);
                Ok(())
            }
            FILE_STATES => {
                self.states_loaded = 0;
                self.state_valid = [false; SAVE_STATE_SLOTS as usize];
                Ok(())
            }
            _ => Ok(()),
        }
    }

    fn on_during_file_load(&mut self, id: u16, data: &[u8]) {
        match id {
            FILE_ROM => {
                if !self.mirror_failed {
                    if let Err(e) = self.mirror_chunk(self.rom_loaded, data) {
                        log::error!("Mirroring the ROM failed: {}", e);
                        self.mirror_failed = true;
                    }
                }
                if VERIFY_ROM {
                    self.rom_chunks.push((data.len() as u32, Self::checksum(data)));
                }
                self.rom_loaded += data.len() as u32;
            }
            FILE_SAVE => {
                if let Some(loaded) = self.save_loaded.as_mut() {
                    *loaded += data.len() as u32;
                }
            }
            FILE_STATES => self.states_loaded += data.len() as u32,
            _ => {}
        }
    }

    fn on_before_run(&mut self) -> Result<(), String> {
        self.prepare_run().map_err(|e| e.to_string())
    }

    fn on_focus_changed(&mut self, has_focus: bool) {
        if !has_focus {
            // Debug output stall stats
            if let Err(e) = Self::log_stats(&mut Device::lock()) {
                log::warn!("Failed to read stall statistics: {}", e);
            }
        }
    }

    fn get_file_size(&mut self, id: u16) -> Option<u32> {
        match id {
            FILE_SAVE => Some(self.save_size),
            FILE_STATES => {
                // Whole slots up to the last one holding a state (an empty
                // file when there is none).
                let used = self
                    .state_valid
                    .iter()
                    .rposition(|&valid| valid)
                    .map_or(0, |slot| slot as u32 + 1);
                Some(used * SDRAM_SAVE_STATE_SLOT_SIZE)
            }
            _ => None,
        }
    }

    fn on_after_file_save(&mut self, _id: u16, _file: &mut File) -> Result<(), String> {
        Ok(())
    }

    fn load_settings(&mut self) -> Vec<(u16, u32)> {
        // Not persisted: every setting starts at its default.
        Self::get_core_info()
            .settings
            .iter()
            .filter(|setting| !matches!(setting.inner, CoreSettingType::Action { .. }))
            .map(|setting| (setting.id, setting.default))
            .collect()
    }

    fn on_setting_changed(&mut self, id: u16, value: u32) {
        match id {
            SETTING_REGION => self.settings.region = value,
            SETTING_BLEND => self.settings.blend = value != 0,
            SETTING_LATENCY_HIDING => self.settings.latency_hiding = value != 0,
            SETTING_ROM_PATH => self.settings.rom_path = value,
            SETTING_BSRAM_CACHE => self.settings.bsram_cache = value,
            SETTING_STATE_SLOT => {
                self.state_slot = value.min(SAVE_STATE_SLOTS - 1);
                return;
            }
            SETTING_SAVE_STATE => {
                let slot = self.state_slot;
                Self::notify_state_result(self.save_state(slot), "saved to", slot);
                return;
            }
            SETTING_LOAD_STATE => {
                let slot = self.state_slot;
                Self::notify_state_result(self.load_state(slot), "loaded from", slot);
                return;
            }
            // The reset action is written to the core by the core manager.
            _ => return,
        }
        // The config register is written with the initial values before the
        // core runs (`prepare_run`); apply later changes right away.
        if let Some(info) = self.rom_info.as_ref() {
            let config = self.config_value(info);
            if let Err(e) = Device::lock().fpga.write_u32(REG_CONFIG, config) {
                log::warn!("Failed to write the SNES config register: {}", e);
            }
        }
    }
}
