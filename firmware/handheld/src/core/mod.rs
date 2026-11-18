use std::{
    fs::File,
    io::BufReader,
    ops::DerefMut as _,
    path::{Path, PathBuf},
    sync::{LazyLock, Mutex, MutexGuard},
    time::{Duration, Instant},
};

use crate::{
    bitstream,
    device::{drivers::fpga, Device},
    input::InputManager,
    ui,
};

pub use error::*;
pub use files_on_demand::OnDemandFile;
pub use handler::CoreHandler;
use handler::CoreHandlerImpl;
pub use info::*;
use settings::CoreSettings;

mod command;
mod disc;
mod error;
mod file_selection;
mod files;
mod files_on_demand;
mod handler;
pub mod info;
pub mod settings;

static CORE_MANAGER: LazyLock<Mutex<CoreManager>> =
    LazyLock::new(|| Mutex::new(CoreManager::new()));

const PROGRESS_UPDATE_INTERVAL: Duration = Duration::from_millis(250);
const NOTIFY_TIMEOUT: Duration = Duration::from_millis(10);
const SETUP_TIMEOUT: Duration = Duration::from_millis(100);

/// Core debug counters, at the same addresses in every core: stall cycles and
/// run cycles since the last clear. A core may keep more (the SNES splits its
/// stalls by port), which it declares in its core.json; see `log_statistics`.
const REG_STAT_STALLS: u32 = 0x0000_1000;
const REG_STAT_CYCLES: u32 = 0x0000_1004;

pub const DIR_SDCARD: &str = "/sdcard/";
pub const DIR_SETTINGS: &str = "/sdcard/settings/";
pub const CORE_POLL_INTERVAL: Duration = Duration::from_millis(5);

pub struct CoreManager {
    stage: Stage,
    listed_cores: bool,
    core_info: Option<info::CoreInfo>,
    core_handler: CoreHandlerImpl,
    core_settings: Option<CoreSettings>,
    selected_files: Vec<Option<PathBuf>>,
    /// The currently open on-demand file, if any.
    open_on_demand_file: Option<OnDemandFile>,
    run_cartridge: bool,
    /// When the core last got focus, to put a duration on the statistics.
    focus_since: Option<Instant>,
    /// The disc file whose new disc is being chosen while the core runs
    /// (see `begin_disc_change`).
    changing_disc: Option<usize>,
}

#[derive(Copy, Clone, Debug, PartialEq)]
enum Stage {
    Idle,
    LoadInit,
    LoadSelectFile(usize),
    LoadBitstream,
    Running,
}

/// # CoreManager
///
/// Manages the lifecycle of cores.
impl CoreManager {
    fn new() -> Self {
        CoreManager {
            core_info: None,
            listed_cores: false,
            stage: Stage::Idle,
            core_handler: CoreHandlerImpl::None,
            core_settings: None,
            selected_files: Vec::new(),
            open_on_demand_file: None,
            run_cartridge: false,
            focus_since: None,
            changing_disc: None,
        }
    }

    pub fn lock() -> MutexGuard<'static, Self> {
        CORE_MANAGER.lock().unwrap()
    }

    /// Find and list all cores (UI)
    pub fn list_cores(&mut self) {
        if self.listed_cores {
            // Already sent the list to the UI, ignore duplicate request.
            return;
        }

        let list = info::list_cores();
        ui::send(ui::Message::CoreList(list));
    }

    /// Start the process of running a specific core (by ID).
    pub fn run_core(&mut self, id: &str, run_cartridge: bool) {
        log::info!("Run core={id} cart={run_cartridge}");
        assert!(self.core_info.is_none());
        assert!(self.stage == Stage::Idle);
        let core = match info::get_core(id) {
            Ok(core) => {
                self.core_info = Some(core);
                self.core_info.as_ref().unwrap()
            }
            Err(e) => {
                log::error!("Error loading core '{id}': {e}");
                ui::send(ui::Message::CoreLoadError(e));
                return;
            }
        };

        self.core_handler = match core.id.as_str() {
            "Game-Bub.GB" => CoreHandlerImpl::Gameboy(crate::bitstream::gameboy::Gameboy::new()),
            "Game-Bub.GBA" => CoreHandlerImpl::Gba(crate::bitstream::gba::Gba::new()),
            _ => CoreHandlerImpl::None,
        };

        self.stage = Stage::LoadInit;
        self.run_cartridge = run_cartridge;
        self.focus_since = None;
        self.core_settings = Some(Self::load_settings(core));
        if let Some(core_handler) = self.core_handler.get_mut() {
            self.core_settings.as_mut().unwrap().settings = core_handler.load_settings();
        }

        self.selected_files = vec![None; core.files.len()];
        self.next_file_select();
    }

    fn load_settings(core: &CoreInfo) -> CoreSettings {
        fn load(core: &CoreInfo) -> Option<CoreSettings> {
            let file = match File::open(core.get_settings_path()) {
                Ok(file) => file,
                Err(_) => {
                    log::warn!("Failed to open settings file");
                    return None;
                }
            };
            let reader = BufReader::with_capacity(256, file);
            let settings: CoreSettings = match serde_json::from_reader(reader) {
                Ok(x) => x,
                Err(_) => {
                    log::warn!("Failed to parse settings file");
                    return None;
                }
            };
            Some(settings)
        }
        let mut settings = load(core).unwrap_or_default();

        // Only keep file paths that belong to user-selected files.
        settings
            .file_paths
            .retain(|(i, _)| core.files.iter().any(|f| f.id == *i && f.user_selected));

        // Validate and apply defaults.
        settings.settings = core
            .settings
            .iter()
            .map(|setting| {
                let value = settings
                    .settings
                    .iter()
                    .find(|x| x.0 == setting.id)
                    .map(|x| x.1)
                    .filter(|&val| setting.is_valid_value(val))
                    .unwrap_or(setting.default);
                (setting.id, value)
            })
            .collect();

        settings
    }

    /// Temporary transitional method
    /// TODO: remove
    pub fn current_bitstream(&mut self) -> Option<&mut dyn bitstream::Bitstream> {
        self.core_handler.get_mut().map(|c| c.as_legacy_bitstream())
    }

    pub fn prepare_for_power_off(&mut self) {
        if self.stage == Stage::Running {
            if let Err(e) = self.persist_files(|_| true) {
                log::error!("Error saving: {}", e);
            }
            if let Err(e) = self.persist_settings() {
                log::error!("Error saving settings: {}", e);
            }
        }
        self.reset_state();
    }

    pub fn exit_core(&mut self) {
        if self.stage == Stage::Running {
            let _ = self.run_core_command(&[command::CORE_HALT], &mut [], NOTIFY_TIMEOUT);

            if let Err(e) = self.persist_files(|_| true) {
                log::error!("Error saving: {}", e);
            }
            if let Err(e) = self.persist_settings() {
                log::error!("Error saving settings: {}", e);
            }
        }

        self.reset_state();

        // Power off the DAC
        Device::lock()
            .dac
            .set_power_down(0, true)
            .expect("DAC power");

        // And go back to the boot bitstream
        bitstream::program_boot();
    }

    fn reset_state(&mut self) {
        self.core_info = None;
        self.core_handler = CoreHandlerImpl::None;
        self.core_settings = None;
        self.stage = Stage::Idle;
        self.selected_files.clear();
        self.open_on_demand_file = None;
        self.changing_disc = None;

        InputManager::lock().clear_core_input_caps();

        let _ = Device::lock()
            .fpga
            .disable_interrupt(fpga::Irq::CoreRequest);
        Device::lock().set_cart_power(false);
    }

    pub fn reset_core(&mut self) {
        if self.stage != Stage::Running {
            return;
        }
        let _ = self.run_core_command(&[command::CORE_HALT], &mut [], NOTIFY_TIMEOUT);
        let _ = self.run_core_command(&[command::CORE_RUN], &mut [], NOTIFY_TIMEOUT);
    }

    pub fn focus_changed(&mut self, has_focus: bool) {
        {
            let mut device = Device::lock();
            device.dac.set_power_down(0, !has_focus).expect("DAC power");
            let _ = device
                .fpga
                .write_u32(fpga::REG_CTRL_FOCUS, has_focus as u32);

            // Darken video if not focused
            let video_filter = if has_focus { 0 } else { 1 };
            let _ = device
                .fpga
                .write_u32(fpga::REG_CTRL_VIDEO_FILTER, video_filter);
        }

        let _ = self.run_core_command(
            &[command::NOTIFY_FOCUS, has_focus as u32],
            &mut [],
            NOTIFY_TIMEOUT,
        );

        let played = self.focus_since.take();
        if has_focus {
            self.focus_since = Some(Instant::now());
        } else {
            self.log_statistics(played.map(|at| at.elapsed()));
            self.write_dumps();
            if self.stage == Stage::Running {
                if let Err(e) = self.persist_files(|info| info.save_on_pause) {
                    log::error!("Error saving: {}", e);
                }
            }
        }

        if let Some(core_handler) = self.core_handler.get_mut() {
            core_handler.on_focus_changed(has_focus);
        }
    }

    /// Read the core's debug counters, clear them and log them. Called after
    /// the core has been told it lost focus, so it has stopped and the
    /// numbers cover the stretch of play that just ended: the counters only
    /// count while the core runs.
    ///
    /// Every core counts run and stall cycles; the run rate is what they are
    /// for. Anything else is core-specific and comes from its core.json,
    /// logged as a count and as a share of the same cycles (see
    /// `CoreStatistic::percent_of`). A core without the counters reads zero
    /// for both and logs nothing.
    ///
    /// The counters are 32 bits, so the cycle count wraps after a few minutes
    /// of play (200 s at the SNES core's 21.5 MHz) and the rate is then
    /// nonsense. `played` is the wall time the window covers, logged so that
    /// a wrapped count is visible: the cycles per second it implies are the
    /// core's clock rate when nothing wrapped.
    fn log_statistics(&self, played: Option<Duration>) {
        let mut device = Device::lock();
        let fpga = &mut device.fpga;

        let (Ok(cycles), Ok(stalls)) = (
            fpga.read_u32(REG_STAT_CYCLES),
            fpga.read_u32(REG_STAT_STALLS),
        ) else {
            log::warn!("Failed to read core statistics");
            return;
        };
        let _ = fpga.write_u32(REG_STAT_CYCLES, 0);
        let _ = fpga.write_u32(REG_STAT_STALLS, 0);

        let total = cycles as u64 + stalls as u64;
        if total == 0 {
            return;
        }
        let statistics = match &self.core_info {
            Some(core) => core.statistics.as_slice(),
            None => &[],
        };
        let values: Vec<Option<u32>> = statistics
            .iter()
            .map(|statistic| {
                let value = fpga.read_u32(statistic.address).ok();
                let _ = fpga.write_u32(statistic.address, 0);
                value
            })
            .collect();
        drop(device);

        // Paced: the USB console drops what does not fit in its buffer.
        let pace = || std::thread::sleep(Duration::from_millis(2));
        log::info!(
            "Run rate: {:.2}% ({cycles} run, {stalls} stalled, over {:.1} s)",
            100.0 * cycles as f32 / total as f32,
            played.unwrap_or_default().as_secs_f32()
        );
        // Internal RAM (there is no PSRAM): the headroom for static buffers.
        let caps = esp_idf_svc::sys::MALLOC_CAP_INTERNAL;
        let (free, lowest, largest) = unsafe {
            (
                esp_idf_svc::sys::heap_caps_get_free_size(caps),
                esp_idf_svc::sys::heap_caps_get_minimum_free_size(caps),
                esp_idf_svc::sys::heap_caps_get_largest_free_block(caps),
            )
        };
        pace();
        log::info!(
            "  heap: {} KiB free, {} KiB at the lowest, largest block {} KiB",
            free / 1024,
            lowest / 1024,
            largest / 1024
        );
        for (statistic, value) in statistics.iter().zip(values) {
            pace();
            let Some(value) = value else {
                log::warn!("Failed to read statistic {}", statistic.label);
                continue;
            };
            let of = if statistic.percent_of == 0 {
                total
            } else {
                statistic.percent_of as u64
            };
            log::info!(
                "  {}: {value} ({:.2}%)",
                statistic.label,
                100.0 * value as f32 / of as f32
            );
        }
    }

    /// Save the core's debug dumps (see `CoreDump`), the game being paused.
    fn write_dumps(&self) {
        use std::io::Write as _;
        let Some(core) = &self.core_info else { return };
        for dump in &core.dumps {
            let path = core.core_dir.join(dump.file.as_str());
            let start = Instant::now();
            let result = (|| -> Result<(), String> {
                let mut file = File::create(&path).map_err(|e| e.to_string())?;
                let mut scratch = crate::bitstream::SCRATCH
                    .take()
                    .ok_or("scratch buffer busy")?;
                let buf = scratch.deref_mut();
                let mut done = 0;
                while done < dump.size {
                    let n = ((dump.size - done) as usize).min(buf.len());
                    let command = fpga::SpiCommand::new(fpga::FpgaSpiWordSize::Bits32);
                    Device::lock()
                        .fpga
                        .spi_read(
                            Some(fpga::MAX_SPI_READ_CLOCK),
                            command,
                            dump.address + done,
                            &mut buf[..n],
                        )
                        .map_err(|e| format!("{e:?}"))?;
                    file.write_all(&buf[..n]).map_err(|e| e.to_string())?;
                    done += n as u32;
                }
                Ok(())
            })();
            match result {
                Ok(()) => log::info!(
                    "Dumped {} bytes from {:08X} to {} in {} ms",
                    dump.size,
                    dump.address,
                    path.display(),
                    start.elapsed().as_millis()
                ),
                Err(e) => log::warn!("Dump to {} failed: {e}", path.display()),
            }
        }
    }

    /// The running core's disc file that another disc can be put in (the
    /// user-selected `disc`), if it has one.
    fn changeable_disc(&self) -> Option<usize> {
        let core = self.core_info.as_ref()?;
        if self.run_cartridge {
            return None;
        }
        core.files.iter().position(|f| f.disc && f.user_selected)
    }

    /// Start choosing another disc for the running core (the pause menu's
    /// "Change Disc"): the file browser opens on the disc in the drive.
    pub fn begin_disc_change(&mut self) {
        let index = match (self.stage, self.changeable_disc()) {
            (Stage::Running, Some(index)) => index,
            _ => {
                log::warn!("No disc to change");
                return;
            }
        };
        self.changing_disc = Some(index);
        let label = self.core_info.as_ref().unwrap().files[index]
            .label
            .to_string();
        let current = self.selected_files[index].clone();
        let (initial_path, initial_dir) = file_selection::browse_start(current.as_deref());
        ui::send(ui::Message::CoreFileSelectBegin {
            label,
            path: initial_path.to_path_buf(),
            in_game: true,
        });
        self.send_core_file_list(initial_dir);
    }

    /// Put the disc at `path` in the running core's drive, in place of the
    /// one in `changing_disc`. The core reads its discs itself, on demand:
    /// it is told by a write to the disc's file (`FILE_WRITE_START` /
    /// `FILE_WRITE_END` with a size of 1), reads the new disc and lets its
    /// drive see the change. What goes with the disc (the `per_disc` files:
    /// the on-demand files named after it, such as its cue sheet, and save
    /// states) is saved under the old disc's name and loaded again under the
    /// new one's; what goes with the game (a memory card) stays as it is.
    fn change_disc(&mut self, path: PathBuf) {
        let index = self.changing_disc.unwrap();
        match self.change_disc_inner(index, &path) {
            Ok(failed) => {
                self.changing_disc = None;
                let name = path
                    .file_stem()
                    .map(|s| s.to_string_lossy().into_owned())
                    .unwrap_or_default();
                log::info!("Disc changed to {}", path.display());
                let mut text = format!("Inserted {name}");
                if !failed.is_empty() {
                    text += &format!("\n{} failed to load", failed.join(", "));
                }
                ui::send(ui::Message::CoreDiscChanged);
                ui::send(ui::Message::Notification(ui::Notification::new_long(text)));
            }
            Err(e) => {
                log::error!("Disc change to {} failed: {e}", path.display());
                ui::send(ui::Message::CoreFileSelectError(e.to_string()));
            }
        }
    }

    /// Returns the labels of the disc's files that failed to load (the disc
    /// has changed all the same; those slots are left empty).
    fn change_disc_inner(&mut self, index: usize, path: &Path) -> Result<Vec<String>, CoreError> {
        let core = self.core_info.as_ref().unwrap();
        let disc_file = &core.files[index];
        let (disc_id, label) = (disc_file.id, disc_file.label.to_string());
        if !path.is_file() {
            return Err(CoreError::CannotOpenFile(label));
        }
        // The files named after the disc, if this is the file they depend on.
        let goes_with_disc = |info: &CoreFile| info.dependent_on_0 && info.per_disc;
        let dependents: Vec<usize> = if disc_id == 0 {
            (0..core.files.len())
                .filter(|&i| goes_with_disc(&core.files[i]))
                .collect()
        } else {
            Vec::new()
        };
        if !dependents.is_empty() {
            self.persist_files(goes_with_disc)?;
        }

        // The old disc's file (or one named after it) may be open for the core.
        self.open_on_demand_file = None;
        self.selected_files[index] = Some(path.to_path_buf());
        self.remember_path(index, path);

        let mut failed = Vec::new();
        let mut scratch = crate::bitstream::SCRATCH.take().expect("scratch buffer");
        for i in dependents {
            if let Err(e) = self.load_file(i, &mut scratch, None, true) {
                let label = self.core_info.as_ref().unwrap().files[i].label.to_string();
                log::error!("Loading {label} for the new disc failed: {e}");
                failed.push(label);
            }
        }
        drop(scratch);

        // Tell the core; it reads the new disc once this returns (its
        // requests wait for the core manager).
        self.run_core_command(
            &[command::FILE_WRITE_START, disc_id as u32],
            &mut [],
            SETUP_TIMEOUT,
        )?;
        self.run_core_command(
            &[command::FILE_WRITE_END, disc_id as u32, 1, 0],
            &mut [],
            NOTIFY_TIMEOUT,
        )?;
        Ok(failed)
    }

    /// Keep `path` as the file's last choice (the file browser starts there next time).
    fn remember_path(&mut self, index: usize, path: &Path) {
        let settings = self.core_settings.as_mut().unwrap();
        let file_id = self.core_info.as_ref().unwrap().files[index].id;
        settings.file_paths.retain(|x| x.0 != file_id);
        settings.file_paths.push((file_id, path.to_path_buf()));
    }

    fn finish_loading(&mut self) {
        if let Err(e) = self.finish_loading_inner() {
            log::error!("Loading the core failed: {e}");
            self.exit_core();
            ui::send(ui::Message::CoreLoadError(e.to_string()));
        }
    }

    fn finish_loading_inner(&mut self) -> Result<(), CoreError> {
        self.load_bitstream();

        // Wait for the core to be ready for setup.
        self.poll_core_status(command::STATUS_SETUP, SETUP_TIMEOUT)?;
        ui::send(ui::Message::EnterGame);
        ui::send(ui::Message::Redraw);

        // Read core runtime metadata
        let caps = Device::lock()
            .fpga
            .read_u32(fpga::REG_INFO_INPUT_CAPS)
            .unwrap();
        InputManager::lock().set_core_input_caps(caps);

        let cart_power = match self.core_info.as_ref().unwrap().uses_cartridge {
            CoreCartridgeMode::No => false,
            CoreCartridgeMode::Yes => true,
            CoreCartridgeMode::IfSelected => self.run_cartridge,
        };
        if cart_power {
            let mut device = Device::lock();
            // Power turned off in reset_state.
            device.set_cart_power(true);
        }

        // A core that asks is told what this firmware does beyond the stock one.
        if let Some(address) = self.core_info.as_ref().unwrap().firmware_features {
            let _ = Device::lock()
                .fpga
                .write_u32(address, info::FIRMWARE_FEATURES);
        }
        self.load_files()?;

        // Transmit initial settings values
        {
            let info = self.core_info.as_ref().unwrap();
            let settings = &self.core_settings.as_ref().unwrap().settings;
            for &(id, value) in settings {
                let setting = match info.settings.iter().find(|x| x.id == id) {
                    Some(x) => x,
                    None => continue,
                };
                if matches!(setting.inner, CoreSettingType::Action { .. }) {
                    // Actions do not get an initial value
                    continue;
                }

                if let Some(core_handler) = self.core_handler.get_mut() {
                    core_handler.on_setting_changed(id, value);
                }
                self.setting_send(setting, value);
            }
        }

        if let Some(core_handler) = self.core_handler.get_mut() {
            core_handler.on_before_run().map_err(CoreError::Other)?;
        }

        // Start listening for core requests
        let _ = Device::lock().fpga.enable_interrupt(fpga::Irq::CoreRequest);

        // Tell the core we're finished setting up.
        self.run_core_command(&[command::SETUP_COMPLETE], &mut [], NOTIFY_TIMEOUT)?;

        // Wait for the core to be ready for run.
        self.poll_core_status(command::STATUS_CORE_HALT, SETUP_TIMEOUT)?;
        log::info!("Core ready to run");

        // Clear loading bar
        ui::send(ui::Message::GameCanChangeDisc(
            self.changeable_disc().is_some(),
        ));
        ui::send(ui::Message::EnterGame);
        self.stage = Stage::Running;

        // Start core.
        let _ = Device::lock().fpga.write_u32(fpga::REG_CTRL_VIBRATE, 1);

        let _ = self.run_core_command(&[command::CORE_RUN], &mut [], NOTIFY_TIMEOUT);
        self.focus_changed(true);
        Ok(())
    }

    fn load_bitstream(&mut self) {
        assert!(self.stage == Stage::LoadBitstream);

        bitstream::program_fpga(&self.core_info.as_ref().unwrap().bitstream);

        if let Some(core_handler) = self.core_handler.get_mut() {
            core_handler.on_after_program();
        }
    }
}
