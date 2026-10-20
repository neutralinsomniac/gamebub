use core::ffi::{c_int, c_void};
use esp_idf_svc::sys::{self as esp_idf_sys, EspError};

use crate::device::board::SdPins;

pub static MOUNT_POINT: &std::ffi::CStr = c"/sdcard";

#[allow(unused)]
mod constants {
    use esp_idf_svc::sys;

    pub const SDMMC_HOST_FLAG_1BIT: u32 = 1 << 0;
    pub const SDMMC_HOST_FLAG_4BIT: u32 = 1 << 1;
    pub const SDMMC_HOST_FLAG_8BIT: u32 = 1 << 2;
    pub const SDMMC_HOST_FLAG_SPI: u32 = 1 << 3;
    pub const SDMMC_HOST_FLAG_DDR: u32 = 1 << 4;
    pub const SDMMC_HOST_FLAG_DEINIT_ARG: u32 = 1 << 5;

    pub const SDMMC_SLOT_NO_CD: sys::gpio_num_t = sys::gpio_num_t_GPIO_NUM_NC;
    pub const SDMMC_SLOT_NO_WP: sys::gpio_num_t = sys::gpio_num_t_GPIO_NUM_NC;
    pub const SDMMC_SLOT_WIDTH_DEFAULT: u8 = 0;
}
use constants::*;

pub struct Sdcard {
    #[allow(unused)]
    card: *mut esp_idf_sys::sdmmc_card_t,
}
unsafe impl Send for Sdcard {}
unsafe impl Sync for Sdcard {}

pub fn mount_sdcard(pins: &SdPins) -> Result<Sdcard, EspError> {
    let host_config = esp_idf_sys::sdmmc_host_t {
        flags: SDMMC_HOST_FLAG_1BIT
            | SDMMC_HOST_FLAG_4BIT
            | SDMMC_HOST_FLAG_DDR
            | SDMMC_HOST_FLAG_DEINIT_ARG,
        slot: 1,
        max_freq_khz: esp_idf_sys::SDMMC_FREQ_HIGHSPEED as c_int,
        io_voltage: 3.3,
        init: Some(esp_idf_sys::sdmmc_host_init),
        set_bus_width: Some(esp_idf_sys::sdmmc_host_set_bus_width),
        get_bus_width: Some(esp_idf_sys::sdmmc_host_get_slot_width),
        set_bus_ddr_mode: Some(esp_idf_sys::sdmmc_host_set_bus_ddr_mode),
        set_card_clk: Some(esp_idf_sys::sdmmc_host_set_card_clk),
        set_cclk_always_on: Some(esp_idf_sys::sdmmc_host_set_cclk_always_on),
        do_transaction: Some(esp_idf_sys::sdmmc_host_do_transaction),
        io_int_enable: Some(esp_idf_sys::sdmmc_host_io_int_enable),
        io_int_wait: Some(esp_idf_sys::sdmmc_host_io_int_wait),
        command_timeout_ms: 0,
        get_real_freq: Some(esp_idf_sys::sdmmc_host_get_real_freq),
        __bindgen_anon_1: esp_idf_sys::sdmmc_host_t__bindgen_ty_1 {
            deinit_p: Some(esp_idf_sys::sdmmc_host_deinit_slot),
        },
        input_delay_phase: esp_idf_sys::sdmmc_delay_phase_t_SDMMC_DELAY_PHASE_0,
        set_input_delay: Some(esp_idf_sys::sdmmc_host_set_input_delay),
        dma_aligned_buffer: std::ptr::null_mut(),
        pwr_ctrl_handle: std::ptr::null_mut(),
        get_dma_info: Some(esp_idf_sys::sdmmc_host_get_dma_info),
        current_limit: esp_idf_sys::sdmmc_current_limit_t_SDMMC_CURRENT_LIMIT_200MA,
        driver_strength: esp_idf_sys::sdmmc_driver_strength_t_SDMMC_DRIVER_STRENGTH_B,
        is_slot_set_to_uhs1: Some(esp_idf_sys::sdmmc_host_is_slot_set_to_uhs1),
    };

    let slot_config = esp_idf_sys::sdmmc_slot_config_t {
        __bindgen_anon_1: esp_idf_sys::sdmmc_slot_config_t__bindgen_ty_1 {
            gpio_cd: pins.cd.unwrap_or(SDMMC_SLOT_NO_CD),
        },
        __bindgen_anon_2: esp_idf_sys::sdmmc_slot_config_t__bindgen_ty_2 {
            gpio_wp: SDMMC_SLOT_NO_WP,
        },
        width: 4,
        flags: 0,
        clk: pins.clk,
        cmd: pins.cmd,
        d0: pins.d0,
        d1: pins.d1,
        d2: pins.d2,
        d3: pins.d3,
        d4: esp_idf_sys::gpio_num_t_GPIO_NUM_NC,
        d5: esp_idf_sys::gpio_num_t_GPIO_NUM_NC,
        d6: esp_idf_sys::gpio_num_t_GPIO_NUM_NC,
        d7: esp_idf_sys::gpio_num_t_GPIO_NUM_NC,
    };

    let mount_config = esp_idf_sys::esp_vfs_fat_sdmmc_mount_config_t {
        format_if_mount_failed: false,
        max_files: 4,
        allocation_unit_size: 16 * 1024,
        disk_status_check_enable: false,
        use_one_fat: false,
    };

    log::info!("Mounting sdcard on slot {}", host_config.slot);
    let mut card: *mut esp_idf_sys::sdmmc_card_t = std::ptr::null_mut();
    let sdmmc_mount_result = unsafe {
        esp_idf_sys::esp_vfs_fat_sdmmc_mount(
            MOUNT_POINT.as_ptr(),
            &host_config,
            &slot_config as *const esp_idf_sys::sdmmc_slot_config_t as *const c_void,
            &mount_config,
            &mut card,
        )
    };
    EspError::convert(sdmmc_mount_result)?;
    log_card_info(card);

    // Configure TinyUSB MSC driver with the sdcard. This doesn't actually expose it
    // over USB, but it configures it for if/when we actually do install tinyusb.
    let tinyusb_msc_sdmmc_config = esp_idf_sys::tinyusb_msc_storage_config_t {
        medium: esp_idf_sys::tinyusb_msc_storage_config_t__bindgen_ty_1 { card },
        fat_fs: esp_idf_sys::tinyusb_msc_fatfs_config_t {
            base_path: std::ptr::null_mut(),
            config: mount_config,
            do_not_format: true,
            format_flags: 0,
        },
        mount_point: 0,
    };
    let tinyusb_result = unsafe {
        esp_idf_sys::tinyusb_msc_new_storage_sdmmc(&tinyusb_msc_sdmmc_config, std::ptr::null_mut())
    };
    EspError::convert(tinyusb_result)?;

    Ok(Sdcard { card })
}

/// Initialize (and then unmount) the microSD card so that its internal controller
/// enters a low-power standby mode.
/// This prevents excessive power draw during deep sleep (if the microSD card remains
/// powered).
pub fn init_idle(pins: &SdPins) {
    if let Ok(sdcard) = mount_sdcard(pins) {
        let _ = sdcard.unmount();
    }
}

impl Sdcard {
    /// Unmount the SD card.
    pub fn unmount(self) -> Result<(), EspError> {
        let result =
            unsafe { esp_idf_sys::esp_vfs_fat_sdcard_unmount(MOUNT_POINT.as_ptr(), self.card) };
        EspError::convert(result)
    }
}

/// Log how the card is driven (clock, bus width) and its cluster size: a
/// diagnostic for slow file transfers. FatFs reads or writes at most one
/// cluster per command, and the card spends about 1.4 ms on each command
/// (measured with 4 and 64 KiB reads: 1.6 and 4.6 ms), so 4 KiB clusters
/// make transfers four to eight times slower than 32 KiB ones.
fn log_card_info(card: *mut esp_idf_sys::sdmmc_card_t) {
    const SECTOR: usize = 512;
    let c = unsafe { &*card };
    log::info!(
        "SD card: {} kHz (limit {} kHz), {}-bit bus{}, {} MiB",
        c.real_freq_khz,
        c.max_freq_khz,
        1 << c.log_bus_width(),
        if c.is_ddr() != 0 { ", DDR" } else { "" },
        (c.csd.capacity as u64 * c.csd.sector_size as u64) >> 20,
    );

    // Words: the SDMMC DMA needs a word-aligned buffer in internal RAM.
    let mut words = vec![0u32; SECTOR / 4];
    let mut read = |sector: usize| -> Result<&[u8], EspError> {
        EspError::convert(unsafe {
            esp_idf_sys::sdmmc_read_sectors(card, words.as_mut_ptr() as *mut c_void, sector, 1)
        })?;
        Ok(unsafe { std::slice::from_raw_parts(words.as_ptr() as *const u8, SECTOR) })
    };

    // The cluster size, from the FAT boot sector: sector 0, or the first
    // partition's.
    let cluster = (|| -> Result<Option<usize>, EspError> {
        let mut boot = read(0)?;
        let is_fat =
            |s: &[u8]| &s[82..87] == b"FAT32" || &s[54..59] == b"FAT16" || &s[54..59] == b"FAT12";
        if !is_fat(boot) {
            let start = u32::from_le_bytes(boot[0x1C6..0x1CA].try_into().unwrap()) as usize;
            boot = read(start)?;
        }
        Ok(is_fat(boot).then(|| {
            u16::from_le_bytes(boot[11..13].try_into().unwrap()) as usize * boot[13] as usize
        }))
    })();
    match cluster {
        Ok(Some(bytes)) => log::info!("SD card: FAT, {} KiB clusters", bytes / 1024),
        Ok(None) => log::info!("SD card: no FAT boot sector found"),
        Err(e) => log::warn!("SD card: reading the boot sector failed: {e}"),
    }
}
