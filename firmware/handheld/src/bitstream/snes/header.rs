//! SNES ROM header detection and cartridge type decoding.
//!
//! This is a port of the ROM analysis done on the ARM side of MiSTer
//! (`Main_MiSTer/support/snes/snes.cpp`), which produces the `ROM_TYPE`,
//! `ROM_MASK`, `RAM_MASK`, `RAM_SIZE` and `PAL` inputs of the SNES core.
//! Unlike the original it works on a seekable file instead of an in-memory
//! copy of the whole ROM, since the MCU cannot hold multi-megabyte ROMs.

use std::io::{self, Read, Seek, SeekFrom};

const CANDIDATE_HEADERS: [u32; 3] = [0x007FC0, 0x00FFC0, 0x40FFC0];

// Offsets within the 64-byte internal header.
const MAPPER: usize = 0x15;
const ROM_TYPE: usize = 0x16;
const ROM_SIZE: usize = 0x17;
const RAM_SIZE: usize = 0x18;
const CART_REGION: usize = 0x19;
const COMPANY: usize = 0x1A;
const COMPLEMENT: usize = 0x1C;
const CHECKSUM: usize = 0x1E;
const RESET_VECTOR: usize = 0x3C;

/// Bytes read before the header (extended header) and the header itself.
const PRE: usize = 16;
const WINDOW: usize = PRE + 64;

const CC92_HEADER: [u8; 32] = [
    0x00, 0x08, 0x22, 0x02, 0x1C, 0x00, 0x10, 0x00, 0x08, 0x65, 0x80, 0x84, 0x20, 0x00, 0x22, 0x25,
    0x00, 0x83, 0x0C, 0x80, 0x10, 0x00, 0x00, 0xA0, 0x80, 0x01, 0x80, 0x80, 0x00, 0x01, 0x02, 0x2D,
];
const PF94_10K_HEADER: [u8; 32] = [
    0xC9, 0x80, 0x80, 0x44, 0x15, 0x00, 0x62, 0x09, 0x29, 0xA0, 0x52, 0x70, 0x50, 0x12, 0x05, 0x35,
    0x31, 0x63, 0xC0, 0x22, 0x01, 0x80, 0xC2, 0x3A, 0x6C, 0xB0, 0xE8, 0x4A, 0x11, 0x20, 0xC0, 0xF8,
];
const PF94_1M_HEADER: [u8; 64] = [
    0x50, 0x52, 0x45, 0x48, 0x49, 0x53, 0x54, 0x4F, 0x52, 0x49, 0x4B, 0x20, 0x4D, 0x41, 0x4E, 0x20,
    0x20, 0x20, 0x20, 0x20, 0x20, 0x30, 0x00, 0x0A, 0x00, 0x01, 0x33, 0x00, 0xFF, 0xFF, 0x00, 0x00,
    0xFF, 0xFF, 0xFF, 0xFF, 0x2B, 0x80, 0x2B, 0x80, 0x2B, 0x80, 0xFE, 0x91, 0x2B, 0x80, 0xA4, 0xF7,
    0xFF, 0xFF, 0xFF, 0xFF, 0x2B, 0x80, 0x2B, 0x80, 0x2B, 0x80, 0x75, 0xF7, 0x00, 0x80, 0xA4, 0xF7,
];

/// Coprocessor / mapper as encoded in the upper nibble of `ROM_TYPE`.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum Chip {
    None,
    Sufami,
    Bsx,
    Cx4,
    Sdd1,
    Sa1,
    Gsu,
    Dsp1,
    Dsp2,
    Dsp3,
    Dsp4,
    Obc1,
    Spc7110,
    Cc92,
    Pf94,
    Unknown(u8),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RomInfo {
    /// Offset of the ROM data in the file (512 if there is a copier header).
    pub data_offset: u64,
    /// Size of the ROM data (excluding the copier header).
    pub rom_size: u32,
    /// Offset of the internal header in the ROM data, if found.
    pub header_offset: Option<u32>,
    /// Game title from the header (Shift-JIS/ASCII, trailing spaces trimmed).
    pub title: [u8; 21],
    /// `ROM_TYPE` input of the core.
    pub rom_type: u8,
    /// Size code: ROM is mirrored to `1024 << rom_size_code` bytes.
    pub rom_size_code: u8,
    /// Size code: backup RAM is `1024 << ram_size_code` bytes (0 = none).
    pub ram_size_code: u8,
    /// PAL region.
    pub pal: bool,
}

impl RomInfo {
    /// Size the ROM must be padded/mirrored to in SDRAM.
    pub fn padded_rom_size(&self) -> u32 {
        1024u32 << self.rom_size_code
    }

    pub fn rom_mask(&self) -> u32 {
        self.padded_rom_size() - 1
    }

    pub fn ram_size(&self) -> u32 {
        if self.ram_size_code == 0 {
            0
        } else {
            1024u32 << self.ram_size_code
        }
    }

    pub fn ram_mask(&self) -> u32 {
        self.ram_size().saturating_sub(1)
    }

    pub fn chip(&self) -> Chip {
        match self.rom_type >> 4 {
            0x0 | 0x1 => Chip::None,
            0x2 => Chip::Sufami,
            0x3 => Chip::Bsx,
            0x4 => Chip::Cx4,
            0x5 => Chip::Sdd1,
            0x6 => Chip::Sa1,
            0x7 => Chip::Gsu,
            0x8 => Chip::Dsp1,
            0x9 => Chip::Dsp2,
            0xA => Chip::Dsp3,
            0xB => Chip::Dsp4,
            0xC => Chip::Obc1,
            0xD => Chip::Spc7110,
            0xE => Chip::Cc92,
            0xF => Chip::Pf94,
            x => Chip::Unknown(x),
        }
    }

    pub fn title_str(&self) -> String {
        String::from_utf8_lossy(&self.title)
            .trim_end_matches(|c| c == ' ' || c == '\0')
            .to_string()
    }
}

fn read_at<R: Read + Seek>(file: &mut R, offset: u64, buf: &mut [u8]) -> io::Result<bool> {
    file.seek(SeekFrom::Start(offset))?;
    let mut filled = 0;
    while filled < buf.len() {
        let n = file.read(&mut buf[filled..])?;
        if n == 0 {
            return Ok(false);
        }
        filled += n;
    }
    Ok(true)
}

/// Score the plausibility of an internal header at `addr` (see snes.cpp).
fn score_header<R: Read + Seek>(
    file: &mut R,
    base: u64,
    size: u32,
    addr: u32,
    window: &mut [u8; WINDOW],
) -> io::Result<u32> {
    if size < addr + 64 || addr < PRE as u32 {
        return Ok(0);
    }
    if !read_at(file, base + (addr - PRE as u32) as u64, window)? {
        return Ok(0);
    }
    let hdr = &window[PRE..];
    let mut score: i32 = 0;

    let reset_vector = u16::from_le_bytes([hdr[RESET_VECTOR], hdr[RESET_VECTOR + 1]]);
    let checksum = u16::from_le_bytes([hdr[CHECKSUM], hdr[CHECKSUM + 1]]);
    let complement = u16::from_le_bytes([hdr[COMPLEMENT], hdr[COMPLEMENT + 1]]);

    // $00:[0000-7fff] is RAM/MMIO; the reset vector must point into ROM.
    if reset_vector < 0x8000 {
        return Ok(0);
    }

    // First opcode executed at the reset vector.
    let mut op = [0u8; 1];
    let op_addr = (addr & !0x7FFF) | (reset_vector as u32 & 0x7FFF);
    if !read_at(file, base + op_addr as u64, &mut op)? {
        return Ok(0);
    }
    let resetop = op[0];
    let mapper = hdr[MAPPER] & !0x10;

    // Most likely opcodes: sei, clc, sec, stz, jmp, jml
    if matches!(resetop, 0x78 | 0x18 | 0x38 | 0x9C | 0x4C | 0x5C) {
        score += 8;
    }
    // Plausible: rep, sep, lda/ldx/ldy, jsr, jsl
    if matches!(
        resetop,
        0xC2 | 0xE2 | 0xAD | 0xAE | 0xAC | 0xAF | 0xA9 | 0xA2 | 0xA0 | 0x20 | 0x22
    ) {
        score += 4;
    }
    // Implausible: rti, rts, rtl, cmp, cpx, cpy
    if matches!(resetop, 0x40 | 0x60 | 0x6B | 0xCD | 0xEC | 0xCC) {
        score -= 4;
    }
    // Least likely: brk, cop, stp, wdm, sbc
    if matches!(resetop, 0x00 | 0x02 | 0xDB | 0x42 | 0xFF) {
        score -= 8;
    }

    if checksum.wrapping_add(complement) == 0xFFFF && checksum != 0 && complement != 0 {
        score += 4;
    }

    if addr == 0x007FC0 && mapper == 0x20 {
        score += 2;
    }
    if addr == 0x00FFC0 && mapper == 0x21 {
        score += 2;
    }
    if addr == 0x007FC0 && mapper == 0x22 {
        score += 2;
    }
    if addr == 0x40FFC0 && mapper == 0x25 {
        score += 2;
    }

    if hdr[COMPANY] == 0x33 {
        score += 2;
    }
    if hdr[ROM_TYPE] < 0x08 {
        score += 1;
    }
    if hdr[ROM_SIZE] < 0x10 {
        score += 1;
    }
    if hdr[RAM_SIZE] < 0x08 {
        score += 1;
    }
    if hdr[CART_REGION] < 14 {
        score += 1;
    }

    Ok(score.max(0) as u32)
}

fn find_header<R: Read + Seek>(file: &mut R, base: u64, size: u32) -> io::Result<Option<u32>> {
    let mut window = [0u8; WINDOW];
    let score_lo = score_header(file, base, size, CANDIDATE_HEADERS[0], &mut window)?;
    let score_hi = score_header(file, base, size, CANDIDATE_HEADERS[1], &mut window)?;
    let mut score_ex = score_header(file, base, size, CANDIDATE_HEADERS[2], &mut window)?;
    if score_ex > 0 {
        // Favor ExHiROM on images > 32 Mbit.
        score_ex += 4;
    }

    Ok(if score_lo >= score_hi && score_lo >= score_ex {
        (score_lo > 0).then_some(CANDIDATE_HEADERS[0])
    } else if score_hi >= score_ex {
        (score_hi > 0).then_some(CANDIDATE_HEADERS[1])
    } else {
        (score_ex > 0).then_some(CANDIDATE_HEADERS[2])
    })
}

/// Analyze a ROM file of `file_size` bytes.
pub fn analyze<R: Read + Seek>(file: &mut R, file_size: u64) -> io::Result<RomInfo> {
    // Strip a 512-byte copier header.
    let (base, size) = if file_size & 512 != 0 {
        (512u64, file_size - 512)
    } else {
        (0u64, file_size)
    };
    let size = size.min(u32::MAX as u64) as u32;

    let mut info = RomInfo {
        data_offset: base,
        rom_size: size,
        header_offset: None,
        title: [b' '; 21],
        rom_type: 0,
        rom_size_code: 0x0C,
        ram_size_code: 0,
        pal: false,
    };

    let header_offset = find_header(file, base, size)?;

    let mut bsx_probe = [0u8; 64];
    let have_lo = size >= 0x8000 && read_at(file, base + 0x7FC0, &mut bsx_probe)?;
    let is_bsx_bios = have_lo && &bsx_probe[..21] == b"Satellaview BS-X     ";
    let is_cc92 = have_lo && bsx_probe[..32] == CC92_HEADER;
    let is_pf94 = have_lo && (bsx_probe[..32] == PF94_10K_HEADER || bsx_probe[..64] == PF94_1M_HEADER);

    let mut sufami_probe = [0u8; 14];
    let is_sufami = size >= 14 && read_at(file, base, &mut sufami_probe)? && &sufami_probe == b"BANDAI SFC-ADX";

    // Recompute the ROM size code from the actual file size.
    let mut romsz: u8 = 15;
    let mut s = size.wrapping_sub(1);
    if s & 0xFF00_0000 == 0 {
        while s & 0x0100_0000 == 0 {
            romsz -= 1;
            s <<= 1;
        }
    }
    info.rom_size_code = romsz;

    let Some(addr) = header_offset else {
        return Ok(info);
    };
    info.header_offset = Some(addr);

    let mut window = [0u8; WINDOW];
    if !read_at(file, base + (addr - PRE as u32) as u64, &mut window)? {
        return Ok(info);
    }
    let ext = &window[..PRE];
    let hdr = &window[PRE..];
    info.title.copy_from_slice(&hdr[..21]);

    let mut ramsz = hdr[RAM_SIZE];
    if ramsz >= 0x09 {
        ramsz = 0;
    }

    let has_bsx_slot = ext[PRE - 14] == b'Z'
        && ext[PRE - 11] == b'J'
        && (ext[PRE - 13].is_ascii_uppercase() || ext[PRE - 13].is_ascii_digit())
        && (hdr[COMPANY] == 0x33 || (ext[PRE - 10] == 0x00 && ext[PRE - 4] == 0x00));

    // Rom type: 0-LoROM, 1-HiROM, 2-ExHiROM, 3-Special LoROM
    let mut rom_type: u8 = match addr {
        0x00FFC0 => 1,
        0x40FFC0 => 2,
        _ if has_bsx_slot => 3,
        _ => 0,
    };

    let mapper = hdr[MAPPER];
    let rtype = hdr[ROM_TYPE];
    let company = hdr[COMPANY];

    if is_bsx_bios {
        rom_type = 0x30;
    } else if is_sufami {
        // Sufami Turbo (base cart only; the multi-cart layout is not supported).
        rom_type = 0x20;
        const ROM_SZ_TBL: [u8; 9] = [0, 7, 8, 9, 9, 10, 10, 10, 10];
        const RAM_SZ_TBL: [u8; 5] = [0, 1, 2, 3, 3];
        info.rom_size_code = if hdr[0x36] >= 8 { ROM_SZ_TBL[8] } else { ROM_SZ_TBL[(hdr[0x36] & 0x0F) as usize] };
        ramsz = if hdr[0x37] >= 4 { RAM_SZ_TBL[4] } else { RAM_SZ_TBL[(hdr[0x37] & 0x07) as usize] };
    } else if is_cc92 {
        rom_type = 0xE4;
        ramsz = 3;
    } else if is_pf94 {
        rom_type = 0xF4;
        ramsz = 3;
    } else {
        // DSPn types 8..B, OBC1 type C
        if mapper == 0x20 && rtype == 0x03 {
            rom_type |= 0x84; // DSP1
        } else if mapper == 0x21 && rtype == 0x03 {
            rom_type |= 0x80; // DSP1B
        } else if mapper == 0x30 && rtype == 0x05 && company != 0xB2 {
            rom_type |= 0x80; // DSP1B
        } else if mapper == 0x31 && (rtype == 0x03 || rtype == 0x05) {
            rom_type |= 0x80; // DSP1B
        } else if mapper == 0x20 && rtype == 0x05 {
            rom_type |= 0x90; // DSP2
        } else if mapper == 0x30 && rtype == 0x05 && company == 0xB2 {
            rom_type |= 0xA0; // DSP3
        } else if mapper == 0x30 && rtype == 0x03 {
            rom_type |= 0xB0; // DSP4
        } else if mapper == 0x30 && rtype == 0xF6 {
            rom_type |= 0x88; // ST010
            ramsz = 1;
            if hdr[ROM_SIZE] < 10 {
                rom_type |= 0x20; // ST011
            }
        } else if mapper == 0x30 && rtype == 0x25 {
            rom_type |= 0xC0; // OBC1
        }

        if mapper == 0x3A && (rtype == 0xF5 || rtype == 0xF9) {
            rom_type |= 0xD0; // SPC7110
            if rtype == 0xF9 {
                rom_type |= 0x08; // with RTC
            }
        }

        if mapper == 0x35 && rtype == 0x55 {
            rom_type |= 0x08; // S-RTC (+ExHiROM)
        }

        if mapper == 0x20 && rtype == 0xF3 {
            rom_type |= 0x40; // CX4
        }

        if mapper == 0x32 && (rtype == 0x43 || rtype == 0x45) {
            if info.rom_size_code < 14 {
                rom_type |= 0x50; // SDD1 (except Star Ocean un-SDD1)
            }
        }

        if mapper == 0x23 && matches!(rtype, 0x32 | 0x33 | 0x34 | 0x35) {
            rom_type |= 0x60; // SA1
        }

        if mapper == 0x20 && matches!(rtype, 0x13 | 0x14 | 0x15 | 0x1A) {
            ramsz = ext[PRE - 3];
            if ramsz == 0xFF {
                ramsz = 5; // StarFox
            }
            if ramsz > 6 {
                ramsz = 6;
            }
            rom_type |= 0x70; // GSU
        }
    }

    let region = hdr[CART_REGION];
    info.pal = ((0x02..=0x0C).contains(&region) || region == 0x11) && !is_sufami && !is_cc92 && !is_pf94;
    info.rom_type = rom_type;
    info.ram_size_code = ramsz;
    Ok(info)
}

/// Map an address at or beyond the end of a non-power-of-two ROM back to the
/// ROM byte that a real cartridge would mirror there (see snes.cpp).
pub fn mirror_address(mut addr: u32, mut size: u32) -> u32 {
    if size == 0 {
        return 0;
    }
    let mut base = 0u32;
    let mut mask = 1u32;
    while mask < size {
        mask <<= 1;
    }
    while addr >= size {
        while mask != 0 && addr & mask == 0 {
            mask >>= 1;
        }
        if mask == 0 {
            return addr % size;
        }
        addr -= mask;
        if size > mask {
            size -= mask;
            base += mask;
        }
        mask >>= 1;
    }
    base + addr
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn make_lorom(size: usize, title: &[u8], mapper: u8, rtype: u8, ram: u8, region: u8) -> Vec<u8> {
        let mut rom = vec![0u8; size];
        let h = 0x7FC0;
        rom[h..h + title.len()].copy_from_slice(title);
        rom[h + MAPPER] = mapper;
        rom[h + ROM_TYPE] = rtype;
        rom[h + ROM_SIZE] = 0x08;
        rom[h + RAM_SIZE] = ram;
        rom[h + CART_REGION] = region;
        rom[h + COMPANY] = 0x01;
        rom[h + CHECKSUM] = 0x34;
        rom[h + CHECKSUM + 1] = 0x12;
        rom[h + COMPLEMENT] = 0xCB;
        rom[h + COMPLEMENT + 1] = 0xED;
        rom[h + RESET_VECTOR] = 0x00;
        rom[h + RESET_VECTOR + 1] = 0x80;
        rom[0] = 0x78; // sei
        rom
    }

    #[test]
    fn detects_lorom() {
        let rom = make_lorom(256 * 1024, b"TEST", 0x20, 0x02, 0x03, 0x01);
        let info = analyze(&mut Cursor::new(&rom), rom.len() as u64).unwrap();
        assert_eq!(info.header_offset, Some(0x7FC0));
        assert_eq!(info.rom_type, 0x00);
        assert_eq!(info.rom_size_code, 8);
        assert_eq!(info.padded_rom_size(), 256 * 1024);
        assert_eq!(info.ram_size(), 8 * 1024);
        assert!(!info.pal);
        assert_eq!(info.title_str(), "TEST");
        assert_eq!(info.chip(), Chip::None);
    }

    #[test]
    fn detects_copier_header_and_dsp1() {
        let mut rom = vec![0u8; 512];
        rom.extend(make_lorom(1024 * 1024, b"PILOTWINGS", 0x20, 0x03, 0x00, 0x02));
        let info = analyze(&mut Cursor::new(&rom), rom.len() as u64).unwrap();
        assert_eq!(info.data_offset, 512);
        assert_eq!(info.rom_size, 1024 * 1024);
        assert_eq!(info.rom_type, 0x84);
        assert_eq!(info.chip(), Chip::Dsp1);
        assert!(info.pal);
    }

    #[test]
    fn non_pow2_rom_is_padded() {
        let rom = make_lorom(3 * 1024 * 1024, b"BIG", 0x20, 0x00, 0x00, 0x01);
        let info = analyze(&mut Cursor::new(&rom), rom.len() as u64).unwrap();
        assert_eq!(info.padded_rom_size(), 4 * 1024 * 1024);
    }

    #[test]
    fn mirror_matches_reference() {
        // 3 MiB ROM mirrored into 4 MiB: [2M..3M) mirrors twice into [2M..4M).
        let size = 3 * 1024 * 1024;
        assert_eq!(mirror_address(0, size), 0);
        assert_eq!(mirror_address(size - 1, size), size - 1);
        assert_eq!(mirror_address(3 * 1024 * 1024, size), 2 * 1024 * 1024);
        assert_eq!(mirror_address(4 * 1024 * 1024 - 1, size), 3 * 1024 * 1024 - 1);
        // 2.5 MiB (5 x 512K): the last 512K mirrors over the remaining 1.5M.
        let size = 5 * 512 * 1024;
        assert_eq!(mirror_address(size, size), 2 * 1024 * 1024);
        assert_eq!(mirror_address(size + 512 * 1024, size), 2 * 1024 * 1024);
    }
}
