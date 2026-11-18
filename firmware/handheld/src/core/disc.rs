//! Disc images for cores with a disc drive (a file marked `disc` in the
//! core's `files.json`): the core reads the image itself, through on-demand
//! file requests (`files_on_demand`), and the firmware only names the
//! files that go with it (see [`save_path`]) and offers to change the disc
//! from the pause menu (`CoreManager::begin_disc_change`).

use std::path::{Path, PathBuf};

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
