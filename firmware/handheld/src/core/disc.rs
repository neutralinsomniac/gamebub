//! CD images for cores with a disc drive: a `.cue` sheet with its `.bin`
//! files, or a lone `.bin`, of raw 2352-byte sectors.
//!
//! A file marked `disc` in the core's `files.json` is not transferred: its
//! table of contents goes to the file's address (see [`DiscImage::toc`]),
//! and the core then reads sectors through core requests (see
//! `CoreManager::handle_core_request`).
//!
//! Sector addresses are absolute LBAs, counted from the start of the disc's
//! program area: track 1 starts at 150 (00:02:00), as on a real disc, where
//! the first two seconds are track 1's pregap. Sectors that are not in any
//! file (that pregap, a `PREGAP` in the sheet, beyond the lead-out) read as
//! zeros.
//!
//! The SD card has room for only a few open files (`max_files` where it is
//! mounted), and a cue sheet can name a file per track, so the image's files
//! are opened as the core reads them: the first stays open once opened (on a
//! PlayStation disc it holds the data track, and as the largest file it is
//! the slowest to open: fast seek maps all its clusters then), and the others
//! take turns in one more. After a seek, the core asks for its first sector
//! when the seek starts, so opening a file overlaps the drive's seek time.

use std::{
    fs::File,
    io::{Read, Seek, SeekFrom},
    path::{Path, PathBuf},
    time::Instant,
};

pub const SECTOR_SIZE: usize = 2352;
/// The absolute LBA of the first sector of the first file.
const FIRST_LBA: u32 = 150;
const MAX_TRACKS: usize = 99;

#[derive(Debug)]
struct Track {
    number: u8,
    audio: bool,
    /// The track's first sector (its pregap, if it has one) and last sector.
    start: u32,
    end: u32,
    /// Where the track's index 1 starts (what the table of contents gives as the track's position).
    index1: u32,
    /// The track's first sector that is in a file, which file and which sector there.
    data_start: u32,
    file: usize,
    file_sector: u64,
}

pub struct DiscImage {
    files: Vec<PathBuf>,
    /// The first file, and one other (by index), once opened.
    first: Option<File>,
    other: Option<(usize, File)>,
    tracks: Vec<Track>,
    /// The first sector after the last track.
    lead_out: u32,
}

/// Minutes, seconds and frames of an absolute LBA, in BCD: `0x00MMSSFF`.
fn msf_bcd(lba: u32) -> u32 {
    fn bcd(x: u32) -> u32 {
        ((x / 10) << 4) | (x % 10)
    }
    let minutes = (lba / 75 / 60).min(99);
    let seconds = (lba / 75) % 60;
    let frames = lba % 75;
    (bcd(minutes) << 16) | (bcd(seconds) << 8) | bcd(frames)
}

/// The path of a save file with `extension` for the disc image at `disc`,
/// named after the game rather than the disc: a " (Disc N)" in the file name
/// (Redump's naming, or TOSEC's " (Disc N of M)") is dropped, so that all of a
/// game's discs share their saves. "Game (USA) (Disc 2).cue" -> "Game
/// (USA).mcd".
pub fn save_path(disc: &Path, extension: &str) -> PathBuf {
    const TAG: &str = " (disc ";
    let stem = disc
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or_default();
    // Lowercasing only changes ASCII bytes, so offsets are the same in both.
    let lower = stem.to_ascii_lowercase();
    let game = lower
        .find(TAG)
        .filter(|&at| lower[at + TAG.len()..].starts_with(|c: char| c.is_ascii_digit()))
        .and_then(|at| lower[at..].find(')').map(|len| (at, at + len + 1)))
        .map(|(start, end)| format!("{}{}", &stem[..start], &stem[end..]));
    disc.with_file_name(format!("{}.{extension}", game.as_deref().unwrap_or(stem)))
}

/// `mm:ss:ff` in sectors.
fn parse_msf(s: &str) -> Option<u32> {
    let mut parts = s.split(':').map(|p| p.trim().parse::<u32>().ok());
    let (m, s, f) = (parts.next()??, parts.next()??, parts.next()??);
    if parts.next().is_some() || s >= 60 || f >= 75 {
        return None;
    }
    Some((m * 60 + s) * 75 + f)
}

/// A track being parsed: number, audio, `PREGAP` length, `INDEX 00`, `INDEX 01`.
struct Pending {
    number: u8,
    audio: bool,
    pregap: u32,
    index0: Option<u32>,
    index1: Option<u32>,
}

/// Place a parsed track on the disc: `file_base` is the absolute LBA of the
/// current file's first sector, `shift` the `PREGAP` sectors inserted so far.
fn finish_track(
    pending: Option<Pending>,
    files: &[PathBuf],
    tracks: &mut Vec<Track>,
    file_base: u32,
    shift: &mut u32,
) -> Result<(), String> {
    let Some(p) = pending else { return Ok(()) };
    let index1 = p
        .index1
        .ok_or_else(|| format!("track {} has no INDEX 01", p.number))?;
    if files.is_empty() {
        return Err(format!("track {} before any FILE", p.number));
    }
    *shift += p.pregap;
    let first_in_file = p.index0.unwrap_or(index1);
    let data_start = file_base + *shift + first_in_file;
    let track = Track {
        number: p.number,
        audio: p.audio,
        start: data_start - p.pregap,
        end: 0, // set by the next track, or the lead-out
        index1: file_base + *shift + index1,
        data_start,
        file: files.len() - 1,
        file_sector: first_in_file as u64,
    };
    if let Some(previous) = tracks.last_mut() {
        if track.start <= previous.start {
            return Err(format!(
                "track {} starts before track {}",
                track.number, previous.number
            ));
        }
        previous.end = track.start - 1;
    }
    tracks.push(track);
    Ok(())
}

impl DiscImage {
    /// Open a `.cue` sheet, or a single `.bin` (one data track).
    pub fn open(path: &Path) -> Result<Self, String> {
        let is_cue = path
            .extension()
            .map_or(false, |e| e.eq_ignore_ascii_case("cue"));
        if is_cue {
            Self::open_cue(path)
        } else {
            let sectors = Self::sectors(path)?;
            let track = Track {
                number: 1,
                audio: false,
                start: FIRST_LBA,
                end: FIRST_LBA + sectors - 1,
                index1: FIRST_LBA,
                data_start: FIRST_LBA,
                file: 0,
                file_sector: 0,
            };
            Ok(DiscImage {
                files: vec![path.to_path_buf()],
                first: None,
                other: None,
                tracks: vec![track],
                lead_out: FIRST_LBA + sectors,
            })
        }
    }

    fn sectors(path: &Path) -> Result<u32, String> {
        let size = std::fs::metadata(path)
            .map_err(|e| format!("{}: {e}", path.display()))?
            .len();
        if size == 0 || size % SECTOR_SIZE as u64 != 0 {
            return Err(format!(
                "{}: not a raw image ({size} bytes, not a multiple of {SECTOR_SIZE})",
                path.display()
            ));
        }
        Ok((size / SECTOR_SIZE as u64) as u32)
    }

    /// Parse a cue sheet. Each `FILE` follows the previous one on the disc;
    /// `INDEX` times are positions in the current file; `PREGAP` inserts
    /// silence that is in no file before the track's index 1.
    fn open_cue(path: &Path) -> Result<Self, String> {
        let mut text = String::new();
        File::open(path)
            .and_then(|mut f| f.read_to_string(&mut text))
            .map_err(|e| format!("{}: {e}", path.display()))?;
        let dir = path.parent().unwrap_or(Path::new("/"));

        let mut files: Vec<PathBuf> = Vec::new();
        let mut tracks: Vec<Track> = Vec::new();
        // The absolute LBA of the current file's first sector, before any
        // PREGAP insertion (`shift` adds those).
        let mut file_base = FIRST_LBA;
        let mut file_sectors = 0u32;
        let mut shift = 0u32;

        let mut pending: Option<Pending> = None;
        for line in text.lines() {
            let line = line.trim();
            let (keyword, rest) = line.split_once(char::is_whitespace).unwrap_or((line, ""));
            let rest = rest.trim();
            match keyword.to_ascii_uppercase().as_str() {
                "FILE" => {
                    finish_track(pending.take(), &files, &mut tracks, file_base, &mut shift)?;
                    // FILE "name" TYPE, or FILE name TYPE
                    let (name, kind) = if let Some(quoted) = rest.strip_prefix('"') {
                        let (name, after) = quoted
                            .split_once('"')
                            .ok_or_else(|| format!("bad FILE line: {line}"))?;
                        (name, after.trim())
                    } else {
                        rest.rsplit_once(char::is_whitespace)
                            .ok_or_else(|| format!("bad FILE line: {line}"))?
                    };
                    if !kind.eq_ignore_ascii_case("BINARY") {
                        return Err(format!("{name}: unsupported file type {kind}"));
                    }
                    let file_path: PathBuf = dir.join(name);
                    if !files.is_empty() {
                        file_base += file_sectors;
                    }
                    file_sectors = Self::sectors(&file_path)?;
                    files.push(file_path);
                }
                "TRACK" => {
                    finish_track(pending.take(), &files, &mut tracks, file_base, &mut shift)?;
                    let (number, mode) = rest
                        .split_once(char::is_whitespace)
                        .ok_or_else(|| format!("bad TRACK line: {line}"))?;
                    let number: u8 = number
                        .parse()
                        .map_err(|_| format!("bad TRACK line: {line}"))?;
                    let audio = match mode.trim().to_ascii_uppercase().as_str() {
                        "AUDIO" => true,
                        "MODE1/2352" | "MODE2/2352" => false,
                        other => return Err(format!("track {number}: unsupported mode {other} (raw 2352-byte sectors only)")),
                    };
                    if number as usize != tracks.len() + 1 {
                        return Err(format!("track {number} out of order"));
                    }
                    if tracks.len() >= MAX_TRACKS {
                        return Err("too many tracks".to_string());
                    }
                    pending = Some(Pending { number, audio, pregap: 0, index0: None, index1: None });
                }
                "INDEX" => {
                    let p = pending
                        .as_mut()
                        .ok_or_else(|| format!("INDEX outside a track: {line}"))?;
                    let (index, time) = rest
                        .split_once(char::is_whitespace)
                        .ok_or_else(|| format!("bad INDEX line: {line}"))?;
                    let sector = parse_msf(time).ok_or_else(|| format!("bad INDEX line: {line}"))?;
                    match index.parse::<u32>() {
                        Ok(0) => p.index0 = Some(sector),
                        Ok(1) => p.index1 = Some(sector),
                        Ok(_) => {} // further indexes: positions within the track, not needed
                        Err(_) => return Err(format!("bad INDEX line: {line}")),
                    }
                }
                "PREGAP" => {
                    let p = pending
                        .as_mut()
                        .ok_or_else(|| format!("PREGAP outside a track: {line}"))?;
                    p.pregap = parse_msf(rest).ok_or_else(|| format!("bad PREGAP line: {line}"))?;
                }
                // REM, CATALOG, TITLE, PERFORMER, FLAGS, ISRC, POSTGAP, ...
                _ => {}
            }
        }
        finish_track(pending.take(), &files, &mut tracks, file_base, &mut shift)?;
        if tracks.is_empty() {
            return Err("no tracks".to_string());
        }
        let lead_out = file_base + shift + file_sectors;
        let last = tracks.last_mut().unwrap();
        if last.start >= lead_out {
            return Err(format!("track {} is beyond the end of its file", last.number));
        }
        last.end = lead_out - 1;

        Ok(DiscImage { files, first: None, other: None, tracks, lead_out })
    }

    pub fn log(&self) {
        log::info!("Disc: {} tracks, lead-out at {}", self.tracks.len(), self.lead_out);
        for t in &self.tracks {
            log::info!(
                "  track {:2} {}: {}-{}, index 1 at {}, file {} from sector {}",
                t.number,
                if t.audio { "audio" } else { "data " },
                t.start,
                t.end,
                t.index1,
                t.file,
                t.file_sector,
            );
        }
    }

    /// The table of contents, as the core gets it (little-endian words):
    ///
    /// | Word | Content |
    /// | --- | --- |
    /// | 0 | number of tracks (bits 7-0), the same in BCD (bits 15-8) |
    /// | 1 | lead-out LBA |
    /// | 2 | lead-out position, BCD `0x00MMSSFF` |
    /// | 3 | 0 |
    /// | 4 + 4n | track n+1: first LBA (its pregap, if any) |
    /// | 5 + 4n | last LBA |
    /// | 6 + 4n | index 1 LBA |
    /// | 7 + 4n | index 1 position, BCD `0x00MMSSFF`; bit 31: audio track |
    pub fn toc(&self) -> Vec<u8> {
        let count = self.tracks.len() as u32;
        let mut words = vec![
            count | (((count / 10) << 12) | ((count % 10) << 8)),
            self.lead_out,
            msf_bcd(self.lead_out),
            0,
        ];
        for t in &self.tracks {
            words.extend_from_slice(&[
                t.start,
                t.end,
                t.index1,
                msf_bcd(t.index1) | ((t.audio as u32) << 31),
            ]);
        }
        words.iter().flat_map(|w| w.to_le_bytes()).collect()
    }

    /// Read the sector at absolute LBA `lba`.
    pub fn read_sector(&mut self, lba: u32, buffer: &mut [u8; SECTOR_SIZE]) -> std::io::Result<()> {
        let track = self.tracks.iter().find(|t| lba >= t.start && lba <= t.end);
        let Some(track) = track.filter(|t| lba >= t.data_start) else {
            buffer.fill(0);
            return Ok(());
        };
        let sector = track.file_sector + (lba - track.data_start) as u64;
        let file = self.file(track.file)?;
        file.seek(SeekFrom::Start(sector * SECTOR_SIZE as u64))?;
        file.read_exact(buffer)
    }

    /// File `index`, opened if it is not open yet: the first file stays
    /// open, and any other replaces the one opened before it.
    fn file(&mut self, index: usize) -> std::io::Result<&mut File> {
        let open = |path: &Path| {
            let start = Instant::now();
            let file = File::open(path)
                .map_err(|e| std::io::Error::new(e.kind(), format!("{}: {e}", path.display())))?;
            log::info!("Disc: opened file {index} in {} ms", start.elapsed().as_millis());
            Ok::<_, std::io::Error>(file)
        };
        if index == 0 {
            if self.first.is_none() {
                self.first = Some(open(&self.files[0])?);
            }
            return Ok(self.first.as_mut().unwrap());
        }
        if self.other.as_ref().map(|(i, _)| *i) != Some(index) {
            // Closed first, to free its handle for the next.
            self.other = None;
            self.other = Some((index, open(&self.files[index])?));
        }
        Ok(&mut self.other.as_mut().unwrap().1)
    }
}
