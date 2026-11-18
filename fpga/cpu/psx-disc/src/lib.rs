//! What the PSX core's disc reader knows about CD images, apart from the
//! hardware: cue sheets, the track table the core gets, where a sector is
//! in the image file, and the disc's region. Plain `no_std` code, so that
//! `cargo test --lib --target x86_64-unknown-linux-gnu` runs it on the host.
//!
//! An image is one file: a `.bin` of raw 2352-byte sectors, or of 2048-byte
//! sectors for a cue sheet's `MODE1/2048` track (the raw sector is made up
//! around them, see [`cooked_header`]). A cue sheet may name only that file:
//! the core reads files through the firmware by the IDs in its `files.json`,
//! and the only files it can name there are the selected one and those
//! named like it with another extension.
//!
//! Sector addresses are absolute LBAs, counted from the start of the disc's
//! program area: track 1 starts at 150 (00:02:00), as on a real disc, where
//! the first two seconds are track 1's pregap. Sectors that are not in the
//! file (that pregap, a `PREGAP` in the sheet, beyond the lead-out) read as
//! zeros.

#![cfg_attr(not(test), no_std)]

pub const SECTOR_SIZE: u32 = 2352;
/// The user data of a MODE1 sector: what a 2048-byte-sector image holds.
pub const COOKED_SIZE: u32 = 2048;
/// Where a 2048-byte sector's data starts in the raw sector.
pub const COOKED_OFFSET: u32 = 16;
/// The sync pattern that starts every raw data sector.
pub const SYNC: [u8; 12] = [0x00, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x00];
/// The absolute LBA of the first sector of the file.
pub const FIRST_LBA: u32 = 150;
pub const MAX_TRACKS: usize = 99;
/// The sector holding the license text the region is read from.
pub const LICENSE_SECTOR: u32 = 154;
/// The longest cue sheet line looked at; the rest of a longer one is dropped.
pub const MAX_LINE: usize = 256;

/// Why an image cannot be used (the reader's debug register shows the code).
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Error {
    /// The image file is missing (or could not be opened).
    NoImage = 1,
    /// The image file holds no whole sector.
    EmptyImage = 2,
    /// A cue sheet names more than one file (one per track, as Redump's do):
    /// only the file named like the sheet can be read.
    SeveralFiles = 3,
    /// A track's mode is not AUDIO, MODE1/2352, MODE2/2352 or MODE1/2048.
    UnsupportedMode = 4,
    /// A file type other than BINARY.
    UnsupportedFileType = 5,
    /// Tracks out of order, a track without INDEX 01, more than 99 tracks...
    BadTrack = 6,
    /// A line that does not parse (a bad time, a FILE without a name...).
    BadLine = 7,
    /// No track at all.
    NoTracks = 8,
    /// 2048- and 2352-byte tracks in the one file.
    MixedSectorSizes = 9,
    /// A track starts beyond the end of the file.
    TrackBeyondFile = 10,
    /// The cue sheet is larger than the reader takes.
    CueTooLarge = 11,
    /// The firmware failed a request for the cue sheet.
    CueReadFailed = 12,
}

#[derive(Copy, Clone, Debug, Default, PartialEq, Eq)]
pub struct Track {
    pub number: u8,
    pub audio: bool,
    /// The track's first sector (its pregap, if it has one) and last sector.
    pub start: u32,
    pub end: u32,
    /// Where the track's index 1 starts (what the table of contents gives as
    /// the track's position).
    pub index1: u32,
    /// The track's first sector that is in the file, and which sector of
    /// the file that is.
    pub data_start: u32,
    pub file_sector: u32,
}

/// Where a sector comes from.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum Location {
    /// Nowhere in the file: zeros.
    Zero,
    /// A raw sector at this byte offset in the file.
    Raw(u32),
    /// The 2048 bytes of user data at this byte offset in the file.
    Cooked(u32),
}

/// A disc image's tracks.
#[derive(Clone, Debug)]
pub struct Disc {
    pub tracks: [Track; MAX_TRACKS],
    pub count: usize,
    /// The first sector after the last track.
    pub lead_out: u32,
    /// The file holds 2048-byte sectors of MODE1 user data.
    pub cooked: bool,
}

impl Disc {
    pub const fn empty() -> Self {
        const T: Track = Track { number: 0, audio: false, start: 0, end: 0, index1: 0, data_start: 0, file_sector: 0 };
        Disc { tracks: [T; MAX_TRACKS], count: 0, lead_out: 0, cooked: false }
    }

    pub fn tracks(&self) -> &[Track] {
        &self.tracks[..self.count]
    }

    /// A lone image file of `size` bytes: one data track of raw sectors.
    pub fn single(size: u64) -> Result<Self, Error> {
        let mut disc = Disc::empty();
        disc.set_single(size)?;
        Ok(disc)
    }

    /// [`Disc::single`], in place.
    pub fn set_single(&mut self, size: u64) -> Result<(), Error> {
        let sectors = sector_count(size, false)?;
        let disc = self;
        disc.tracks[0] = Track {
            number: 1,
            audio: false,
            start: FIRST_LBA,
            end: FIRST_LBA + sectors - 1,
            index1: FIRST_LBA,
            data_start: FIRST_LBA,
            file_sector: 0,
        };
        disc.count = 1;
        disc.lead_out = FIRST_LBA + sectors;
        disc.cooked = false;
        Ok(())
    }

    /// Where the sector at absolute LBA `lba` is.
    pub fn locate(&self, lba: u32) -> Location {
        let Some(track) = self.tracks().iter().find(|t| lba >= t.start && lba <= t.end) else {
            return Location::Zero;
        };
        if lba < track.data_start {
            return Location::Zero;
        }
        let sector = track.file_sector + (lba - track.data_start);
        if self.cooked {
            Location::Cooked(sector * COOKED_SIZE)
        } else {
            Location::Raw(sector * SECTOR_SIZE)
        }
    }

    /// The core's track table (the MiSTer's `disk_t`), as (word, value) in
    /// the order written: 0 the track count (and in BCD from bit 8), 1 the
    /// lead-out LBA, 2 its minutes and seconds (BCD), 3 the region from bit
    /// 16 (MiSTer's codes: 0 unknown, 1 Japan, 2 US, 3 Europe); then per
    /// track 4 words: its first LBA (its pregap's; 0 for track 1, as on the
    /// MiSTer), its last LBA, index 1's minutes and seconds (BCD) with bit 16
    /// set for audio, and a word whose write stores the track.
    pub fn trackinfo(&self, region: Option<Region>) -> impl Iterator<Item = (u32, u32)> + '_ {
        let count = self.count as u32;
        let code = match region {
            None => 0,
            Some(Region::Japan) => 1,
            Some(Region::Us) => 2,
            Some(Region::Europe) => 3,
        };
        let header = [count | (bcd(count) << 8), self.lead_out, msf_bcd(self.lead_out) >> 8, code << 16];
        let tracks = self.tracks().iter().enumerate().flat_map(|(n, t)| {
            [
                if n == 0 { 0 } else { t.start },
                t.end,
                ((t.audio as u32) << 16) | (msf_bcd(t.index1) >> 8),
                0,
            ]
        });
        header.into_iter().chain(tracks).enumerate().map(|(i, w)| (i as u32, w))
    }
}

/// The number of sectors in a file of `size` bytes (a partial last sector
/// is not counted).
fn sector_count(size: u64, cooked: bool) -> Result<u32, Error> {
    let sector_size = if cooked { COOKED_SIZE } else { SECTOR_SIZE } as u64;
    match size / sector_size {
        0 => Err(Error::EmptyImage),
        n => Ok(n.min(u32::MAX as u64 / SECTOR_SIZE as u64) as u32),
    }
}

fn bcd(x: u32) -> u32 {
    ((x / 10) << 4) | (x % 10)
}

/// Minutes, seconds and frames of an absolute LBA, in BCD: `0x00MMSSFF`.
pub fn msf_bcd(lba: u32) -> u32 {
    let minutes = (lba / 75 / 60).min(99);
    let seconds = (lba / 75) % 60;
    let frames = lba % 75;
    (bcd(minutes) << 16) | (bcd(seconds) << 8) | bcd(frames)
}

/// The first 16 bytes of the raw MODE1 sector at absolute LBA `lba`: the
/// sync pattern and the header (its address, mode 1). The rest of a sector
/// made up from 2048 bytes of user data is that data and then zeros where
/// the EDC and ECC would be: the core's drive does not check them.
pub fn cooked_header(lba: u32) -> [u8; 16] {
    let msf = msf_bcd(lba);
    let mut h = [0u8; 16];
    h[..12].copy_from_slice(&SYNC);
    h[12] = (msf >> 16) as u8;
    h[13] = (msf >> 8) as u8;
    h[14] = msf as u8;
    h[15] = 1;
    h
}

/// Region codes, as the core's `region` input takes them.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Region {
    Us = 0,
    Japan = 1,
    Europe = 2,
}

/// The region from the license sector's text ("...Sony Computer
/// Entertainment Amer  ica", "... Inc.", "...Euro pe"), as the MiSTer
/// reads it: the first of the texts to end in the sector, of `len` bytes
/// read through `byte` (the sector buffer is the CPU's, not a slice).
pub fn region(len: usize, byte: impl Fn(usize) -> u8) -> Option<Region> {
    const PATTERNS: [(&[u8], Region); 3] = [
        (b"Entertainment Amer  ica", Region::Us),
        (b"Entertainment Inc.", Region::Japan),
        (b"Entertainment Euro pe", Region::Europe),
    ];
    for end in 0..len {
        let last = byte(end);
        for (text, region) in PATTERNS {
            if last == text[text.len() - 1]
                && end + 1 >= text.len()
                && text.iter().rev().enumerate().skip(1).all(|(i, &c)| byte(end - i) == c)
            {
                return Some(region);
            }
        }
    }
    None
}

/// `mm:ss:ff` in sectors.
fn parse_msf(s: &[u8]) -> Option<u32> {
    let mut parts = s.split(|&c| c == b':').map(parse_u32);
    let (m, s, f) = (parts.next()??, parts.next()??, parts.next()??);
    if parts.next().is_some() || s >= 60 || f >= 75 {
        return None;
    }
    Some((m * 60 + s) * 75 + f)
}

fn parse_u32(s: &[u8]) -> Option<u32> {
    let s = trim(s);
    if s.is_empty() || s.len() > 9 {
        return None;
    }
    s.iter().try_fold(0u32, |n, &c| c.is_ascii_digit().then(|| n * 10 + (c - b'0') as u32))
}

fn trim(s: &[u8]) -> &[u8] {
    let start = s.iter().position(|c| !c.is_ascii_whitespace()).unwrap_or(s.len());
    let end = s.iter().rposition(|c| !c.is_ascii_whitespace()).map_or(start, |e| e + 1);
    &s[start..end]
}

/// The first word of `s`, and the rest (trimmed).
fn split_word(s: &[u8]) -> (&[u8], &[u8]) {
    let s = trim(s);
    match s.iter().position(|c| c.is_ascii_whitespace()) {
        Some(at) => (&s[..at], trim(&s[at..])),
        None => (s, &[]),
    }
}

/// A track being parsed.
#[derive(Copy, Clone)]
struct Pending {
    number: u8,
    audio: bool,
    pregap: u32,
    index0: Option<u32>,
    index1: Option<u32>,
}

/// A cue sheet, parsed line by line ([`CueParser::line`]) into a disc and
/// placed on it at the end ([`CueParser::finish`], which needs the file's
/// size). The disc is filled in place (the reader has little stack).
///
/// Each `FILE` follows the previous one on the disc (only one is allowed);
/// `INDEX` times are positions in the file; `PREGAP` inserts silence that is
/// in no file before the track's index 1.
pub struct CueParser<'a> {
    disc: &'a mut Disc,
    files: u32,
    /// The file's sector size, once its first track has given it.
    cooked: Option<bool>,
    /// `PREGAP` sectors inserted so far.
    shift: u32,
    pending: Option<Pending>,
}

impl<'a> CueParser<'a> {
    pub fn new(disc: &'a mut Disc) -> Self {
        disc.count = 0;
        disc.lead_out = 0;
        disc.cooked = false;
        CueParser { disc, files: 0, cooked: None, shift: 0, pending: None }
    }

    /// One line of the sheet (without its line break).
    pub fn line(&mut self, line: &[u8]) -> Result<(), Error> {
        let (keyword, rest) = split_word(line);
        if keyword.eq_ignore_ascii_case(b"FILE") {
            self.finish_track()?;
            // FILE "name" TYPE, or FILE name TYPE: only the type matters.
            let kind = match rest.strip_prefix(b"\"") {
                Some(quoted) => {
                    let close = quoted.iter().position(|&c| c == b'"').ok_or(Error::BadLine)?;
                    trim(&quoted[close + 1..])
                }
                None => {
                    let at = rest.iter().rposition(|c| c.is_ascii_whitespace()).ok_or(Error::BadLine)?;
                    trim(&rest[at..])
                }
            };
            if !kind.eq_ignore_ascii_case(b"BINARY") {
                return Err(Error::UnsupportedFileType);
            }
            self.files += 1;
            if self.files > 1 {
                return Err(Error::SeveralFiles);
            }
        } else if keyword.eq_ignore_ascii_case(b"TRACK") {
            self.finish_track()?;
            let (number, mode) = split_word(rest);
            let number = parse_u32(number).filter(|&n| n >= 1 && n <= MAX_TRACKS as u32).ok_or(Error::BadTrack)?;
            let mode = trim(mode);
            let (audio, cooked) = if mode.eq_ignore_ascii_case(b"AUDIO") {
                (true, false)
            } else if mode.eq_ignore_ascii_case(b"MODE1/2352") || mode.eq_ignore_ascii_case(b"MODE2/2352") {
                (false, false)
            } else if mode.eq_ignore_ascii_case(b"MODE1/2048") {
                (false, true)
            } else {
                return Err(Error::UnsupportedMode);
            };
            if self.files == 0 {
                return Err(Error::BadTrack);
            }
            match self.cooked {
                None => self.cooked = Some(cooked),
                Some(c) if c != cooked => return Err(Error::MixedSectorSizes),
                Some(_) => {}
            }
            if number as usize != self.disc.count + 1 {
                return Err(Error::BadTrack);
            }
            self.pending = Some(Pending { number: number as u8, audio, pregap: 0, index0: None, index1: None });
        } else if keyword.eq_ignore_ascii_case(b"INDEX") {
            let p = self.pending.as_mut().ok_or(Error::BadLine)?;
            let (index, time) = split_word(rest);
            let sector = parse_msf(time).ok_or(Error::BadLine)?;
            match parse_u32(index).ok_or(Error::BadLine)? {
                0 => p.index0 = Some(sector),
                1 => p.index1 = Some(sector),
                _ => {} // further indexes: positions within the track, not needed
            }
        } else if keyword.eq_ignore_ascii_case(b"PREGAP") {
            let p = self.pending.as_mut().ok_or(Error::BadLine)?;
            p.pregap = parse_msf(rest).ok_or(Error::BadLine)?;
        }
        // REM, CATALOG, TITLE, PERFORMER, FLAGS, ISRC, POSTGAP, ...
        Ok(())
    }

    /// Place the pending track on the disc.
    fn finish_track(&mut self) -> Result<(), Error> {
        let Some(p) = self.pending.take() else { return Ok(()) };
        let index1 = p.index1.ok_or(Error::BadTrack)?;
        self.shift += p.pregap;
        let first_in_file = p.index0.unwrap_or(index1);
        let data_start = FIRST_LBA + self.shift + first_in_file;
        let track = Track {
            number: p.number,
            audio: p.audio,
            start: data_start - p.pregap,
            end: 0, // set by the next track, or the lead-out
            index1: FIRST_LBA + self.shift + index1,
            data_start,
            file_sector: first_in_file,
        };
        if self.disc.count == MAX_TRACKS {
            return Err(Error::BadTrack);
        }
        if let Some(previous) = self.disc.tracks[..self.disc.count].last_mut() {
            if track.start <= previous.start {
                return Err(Error::BadTrack);
            }
            previous.end = track.start - 1;
        }
        self.disc.tracks[self.disc.count] = track;
        self.disc.count += 1;
        Ok(())
    }

    /// Complete the disc, given the file's size in bytes.
    pub fn finish(mut self, file_size: u64) -> Result<(), Error> {
        self.finish_track()?;
        if self.disc.count == 0 {
            return Err(Error::NoTracks);
        }
        let cooked = self.cooked.unwrap_or(false);
        let lead_out = FIRST_LBA + self.shift + sector_count(file_size, cooked)?;
        let last = &mut self.disc.tracks[self.disc.count - 1];
        if last.start >= lead_out {
            return Err(Error::TrackBeyondFile);
        }
        last.end = lead_out - 1;
        self.disc.lead_out = lead_out;
        self.disc.cooked = cooked;
        Ok(())
    }
}

/// Splits a byte stream into lines for [`CueParser::line`] (LF or CR LF;
/// a line longer than [`MAX_LINE`] is cut there).
pub struct LineSplitter {
    buf: [u8; MAX_LINE],
    len: usize,
}

impl Default for LineSplitter {
    fn default() -> Self {
        Self::new()
    }
}

impl LineSplitter {
    pub const fn new() -> Self {
        LineSplitter { buf: [0; MAX_LINE], len: 0 }
    }

    /// Feed bytes; `line` gets each line completed by them.
    pub fn feed(
        &mut self,
        bytes: impl Iterator<Item = u8>,
        mut line: impl FnMut(&[u8]) -> Result<(), Error>,
    ) -> Result<(), Error> {
        for b in bytes {
            if b == b'\n' || b == b'\r' {
                if self.len > 0 {
                    line(&self.buf[..self.len])?;
                }
                self.len = 0;
            } else if self.len < MAX_LINE {
                self.buf[self.len] = b;
                self.len += 1;
            }
        }
        Ok(())
    }

    /// The last line, if the stream did not end with a line break.
    pub fn end(&mut self, mut line: impl FnMut(&[u8]) -> Result<(), Error>) -> Result<(), Error> {
        if self.len > 0 {
            line(&self.buf[..self.len])?;
        }
        self.len = 0;
        Ok(())
    }
}

/// Parse a whole cue sheet held in memory (for tests and tools).
pub fn parse_cue(text: &[u8], file_size: u64) -> Result<Disc, Error> {
    let mut disc = Disc::empty();
    let mut parser = CueParser::new(&mut disc);
    let mut lines = LineSplitter::new();
    lines.feed(text.iter().copied(), |l| parser.line(l))?;
    lines.end(|l| parser.line(l))?;
    parser.finish(file_size)?;
    Ok(disc)
}

#[cfg(test)]
mod tests {
    use super::*;

    const S: u64 = SECTOR_SIZE as u64;

    #[test]
    fn single_bin() {
        let disc = Disc::single(1000 * S + 5).unwrap();
        assert_eq!(disc.tracks(), &[Track { number: 1, audio: false, start: 150, end: 1149, index1: 150, data_start: 150, file_sector: 0 }]);
        assert_eq!(disc.lead_out, 1150);
        assert_eq!(disc.locate(150), Location::Raw(0));
        assert_eq!(disc.locate(154), Location::Raw(4 * SECTOR_SIZE));
        assert_eq!(disc.locate(149), Location::Zero);
        assert_eq!(disc.locate(1150), Location::Zero);
        assert_eq!(Disc::single(100).unwrap_err(), Error::EmptyImage);
    }

    /// A Redump-style sheet run through binmerge: data track, then audio
    /// tracks with 2-second INDEX 00 pregaps in the file.
    const MERGED: &str = "FILE \"Game (USA).bin\" BINARY\r\n  TRACK 01 MODE2/2352\r\n    INDEX 01 00:00:00\r\n  TRACK 02 AUDIO\r\n    INDEX 00 04:26:00\r\n    INDEX 01 04:28:00\r\n  TRACK 03 AUDIO\r\n    INDEX 00 07:00:10\r\n    INDEX 01 07:02:10\r\n";

    #[test]
    fn merged_cue() {
        let size = 40000 * S;
        let disc = parse_cue(MERGED.as_bytes(), size).unwrap();
        let t = disc.tracks();
        assert_eq!(t.len(), 3);
        // Track 2's pregap (INDEX 00) is in the file: from 04:26:00 = 19950.
        assert_eq!(t[0], Track { number: 1, audio: false, start: 150, end: 150 + 19950 - 1, index1: 150, data_start: 150, file_sector: 0 });
        assert_eq!(t[1], Track { number: 2, audio: true, start: 150 + 19950, end: 150 + 31510 - 1, index1: 150 + 20100, data_start: 150 + 19950, file_sector: 19950 });
        assert_eq!(t[2].start, 150 + 31510);
        assert_eq!(t[2].end, 150 + 40000 - 1);
        assert_eq!(disc.lead_out, 40150);
        assert_eq!(disc.locate(150 + 20000), Location::Raw(20000 * SECTOR_SIZE));
    }

    #[test]
    fn pregap_is_silence() {
        let cue = "FILE x.bin BINARY\nTRACK 01 MODE1/2352\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nPREGAP 00:02:00\nINDEX 01 00:10:00\n";
        let disc = parse_cue(cue.as_bytes(), 2000 * S).unwrap();
        let t = disc.tracks();
        // Track 2: 150 pregap sectors in no file, then file sector 750 on.
        assert_eq!(t[0].end, 150 + 750 - 1);
        assert_eq!(t[1].start, 150 + 750);
        assert_eq!(t[1].data_start, 150 + 750 + 150);
        assert_eq!(t[1].index1, 150 + 750 + 150);
        assert_eq!(disc.lead_out, 150 + 150 + 2000);
        assert_eq!(disc.locate(150 + 760), Location::Zero);
        assert_eq!(disc.locate(150 + 900), Location::Raw(750 * SECTOR_SIZE));
    }

    #[test]
    fn errors() {
        let two = "FILE \"a (Track 1).bin\" BINARY\nTRACK 01 MODE2/2352\nINDEX 01 00:00:00\nFILE \"a (Track 2).bin\" BINARY\nTRACK 02 AUDIO\nINDEX 01 00:00:00\n";
        assert_eq!(parse_cue(two.as_bytes(), 1000 * S).unwrap_err(), Error::SeveralFiles);
        let mode = "FILE a.bin BINARY\nTRACK 01 MODE2/2336\nINDEX 01 00:00:00\n";
        assert_eq!(parse_cue(mode.as_bytes(), 1000 * S).unwrap_err(), Error::UnsupportedMode);
        let wave = "FILE a.wav WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\n";
        assert_eq!(parse_cue(wave.as_bytes(), 1000 * S).unwrap_err(), Error::UnsupportedFileType);
        let order = "FILE a.bin BINARY\nTRACK 02 MODE2/2352\nINDEX 01 00:00:00\n";
        assert_eq!(parse_cue(order.as_bytes(), 1000 * S).unwrap_err(), Error::BadTrack);
        let no_index = "FILE a.bin BINARY\nTRACK 01 MODE2/2352\n";
        assert_eq!(parse_cue(no_index.as_bytes(), 1000 * S).unwrap_err(), Error::BadTrack);
        assert_eq!(parse_cue(b"REM nothing\n", 1000 * S).unwrap_err(), Error::NoTracks);
        let beyond = "FILE a.bin BINARY\nTRACK 01 MODE2/2352\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 10:00:00\n";
        assert_eq!(parse_cue(beyond.as_bytes(), 1000 * S).unwrap_err(), Error::TrackBeyondFile);
        let time = "FILE a.bin BINARY\nTRACK 01 MODE2/2352\nINDEX 01 00:60:00\n";
        assert_eq!(parse_cue(time.as_bytes(), 1000 * S).unwrap_err(), Error::BadLine);
    }

    #[test]
    fn cooked() {
        let cue = "FILE a.iso BINARY\nTRACK 01 MODE1/2048\nINDEX 01 00:00:00\n";
        let disc = parse_cue(cue.as_bytes(), 300 * COOKED_SIZE as u64).unwrap();
        assert!(disc.cooked);
        assert_eq!(disc.lead_out, 450);
        assert_eq!(disc.locate(152), Location::Cooked(2 * COOKED_SIZE));
        assert_eq!(cooked_header(152), [0, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 0, 0x00, 0x02, 0x02, 1]);
    }

    #[test]
    fn trackinfo_matches_the_old_glue() {
        // The words the glue used to make from the firmware's table of
        // contents (PsxDiscSpec before the reader moved into the core).
        let mut disc = Disc::empty();
        disc.tracks[0] = Track { number: 1, audio: false, start: 150, end: 20149, index1: 150, data_start: 150, file_sector: 0 };
        disc.tracks[1] = Track { number: 2, audio: true, start: 20150, end: 29999, index1: 20300, data_start: 20150, file_sector: 20000 };
        disc.count = 2;
        disc.lead_out = 30000;
        let words: Vec<(u32, u32)> = disc.trackinfo(Some(Region::Europe)).collect();
        assert_eq!(
            words,
            vec![
                (0, 2 | (2 << 8)), (1, 30000), (2, 0x0640), (3, 3 << 16),
                (4, 0), (5, 20149), (6, 0x0002), (7, 0),
                (8, 20150), (9, 29999), (10, (1 << 16) | 0x0430), (11, 0),
            ]
        );
        assert_eq!(disc.trackinfo(None).nth(3), Some((3, 0)));
        let count12: Vec<u32> = { let mut d = disc.clone(); d.count = 12; d.trackinfo(None).map(|w| w.1).take(1).collect() };
        assert_eq!(count12, vec![12 | (0x12 << 8)]);
    }

    #[test]
    fn regions() {
        let sector = |text: &str| {
            let mut s = vec![0x55u8; SECTOR_SIZE as usize];
            let t = format!("          Licensed  by          Sony Computer {text}");
            s[37..37 + t.len()].copy_from_slice(t.as_bytes());
            s
        };
        let find = |s: Vec<u8>| region(s.len(), |i| s[i]);
        assert_eq!(find(sector("Entertainment Amer  ica ")), Some(Region::Us));
        assert_eq!(find(sector("Entertainment Inc.")), Some(Region::Japan));
        assert_eq!(find(sector("Entertainment Euro pe")), Some(Region::Europe));
        assert_eq!(find(vec![0x55u8; 2352]), None);
        // The text across the end of the sector does not count.
        let mut s = vec![0u8; 30];
        s[10..30].copy_from_slice(&b"Entertainment Inc."[..18].iter().chain(b"xx").copied().collect::<Vec<_>>());
        assert_eq!(find(s.clone()), Some(Region::Japan));
        assert_eq!(find(s[..27].to_vec()), None);
    }

    #[test]
    fn long_lines_and_line_ends() {
        let mut cue = String::from("REM ");
        cue.push_str(&"x".repeat(1000));
        cue.push_str("\rFILE a.bin BINARY\r\n\r\nTRACK 01 MODE2/2352\nINDEX 01 00:00:00");
        let disc = parse_cue(cue.as_bytes(), 10 * S).unwrap();
        assert_eq!(disc.count, 1);
        assert_eq!(disc.lead_out, 160);
    }
}
