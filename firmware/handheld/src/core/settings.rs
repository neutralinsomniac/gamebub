use std::path::PathBuf;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

use crate::core::{ActionResult, CoreManager, CoreSetting};
use crate::device::Device;
use crate::ui::CoreSettingUiItem;

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct CoreSettings {
    /// A list of files and their most recently selected paths.
    pub file_paths: Vec<(u16, PathBuf)>,

    /// List of persisted settings.
    pub settings: Vec<(u16, u32)>,
}

impl CoreManager {
    pub fn ui_load_settings(&self) {
        use super::CoreSettingType::*;
        let core = match self.core_info.as_ref() {
            Some(x) => x,
            None => return,
        };

        let list = core
            .settings
            .iter()
            .map(|setting| {
                let id = setting.id;

                // This is the underlying u32 value, which is not the same as the UI value.
                let value = self
                    .core_settings
                    .as_ref()
                    .and_then(|s| s.settings.iter().find(|s| s.0 == id))
                    .map(|(_, value)| *value)
                    .unwrap_or_default();
                let ui_value = match &setting.inner {
                    Action { .. } => 0,
                    Checkbox { .. } => (value != 0) as u32,
                    List { items } => items
                        .iter()
                        .position(|item| item.value == value)
                        .unwrap_or(0) as u32,
                };

                let choices = match &setting.inner {
                    List { items } => items.iter().map(|x| x.label.clone()).collect(),
                    _ => Vec::new(),
                };
                CoreSettingUiItem {
                    id,
                    label: setting.label.clone(),
                    setting_type: match &setting.inner {
                        Action { .. } => crate::ui::slint::CoreSettingType::Action,
                        Checkbox { .. } => crate::ui::slint::CoreSettingType::Checkbox,
                        List { .. } => crate::ui::slint::CoreSettingType::List,
                    },
                    value: ui_value,
                    choices,
                    resume_after: matches!(
                        setting.inner,
                        Action {
                            resume_after: true,
                            ..
                        }
                    ),
                }
            })
            .collect();
        crate::ui::send(crate::ui::Message::CoreSettingsList(list));
    }

    pub fn setting_changed(&mut self, id: u16, ui_value: u32) {
        use super::CoreSettingType::*;
        let core = match self.core_info.as_ref() {
            Some(x) => x,
            None => return,
        };
        let setting = match core.settings.iter().find(|x| x.id == id) {
            Some(x) => x,
            None => return,
        };

        // Save updated value in core settings.
        let value = match &setting.inner {
            Action { value, .. } => *value,
            Checkbox { value } => {
                if ui_value != 0 {
                    *value
                } else {
                    0
                }
            }
            List { items } => items.get(ui_value as usize).map(|x| x.value).unwrap_or(0),
        };
        let list = &mut self.core_settings.as_mut().unwrap().settings;
        if let Some(entry) = list.iter_mut().find(|x| x.0 == id) {
            entry.1 = value;
        } else {
            list.push((id, value));
        }

        log::info!("Core setting {} => {}", setting.label, value);
        let save_after = matches!(setting.inner, Action { save_after: true, .. });
        self.setting_send(setting, value);

        if let Some(core_handler) = self.core_handler.get_mut() {
            core_handler.on_setting_changed(id, value);
        }

        if let Action {
            result: Some(result),
            ..
        } = &setting.inner
        {
            if self.stage == super::Stage::Running {
                let text = self.wait_action_result(&setting.label, result);
                crate::ui::send(crate::ui::Message::Notification(
                    crate::ui::Notification::new_short(text),
                ));
            }
        }

        if save_after {
            self.save_after_action();
        }
    }

    /// Save what a pause saves (the `save_on_pause` files) after an action
    /// that changes them (`save_after`), and say how it went: the core
    /// holds `FILE_READ_START` until such a file is complete.
    fn save_after_action(&mut self) {
        if self.stage != super::Stage::Running {
            return;
        }
        let text = match self.persist_files(|info| info.save_on_pause) {
            Ok(saved) if saved.is_empty() => "Nothing saved".to_string(),
            Ok(saved) => saved
                .iter()
                .map(|label| format!("{label} saved"))
                .collect::<Vec<_>>()
                .join(", "),
            Err(e) => {
                log::error!("Error saving: {}", e);
                format!("Save failed: {e}")
            }
        };
        crate::ui::send(crate::ui::Message::Notification(
            crate::ui::Notification::new_short(text),
        ));
    }

    /// Wait for the core's report on an action (`result`), and return the
    /// message for it.
    fn wait_action_result(&self, label: &str, result: &ActionResult) -> String {
        let deadline = Instant::now() + Duration::from_millis(result.timeout_ms.into());
        let value = loop {
            let value = Device::lock().fpga.read_u32(result.address).unwrap_or(0);
            if value != 0 || Instant::now() >= deadline {
                break value;
            }
            std::thread::sleep(Duration::from_millis(10));
        };
        log::info!("{label}: result {value}");
        match result.messages.iter().find(|m| m.value == value) {
            Some(message) => self.format_action_text(&message.text),
            None if value == 0 => format!("{label}: no answer from the core"),
            None => format!("{label}: result {value}"),
        }
    }

    /// `text` with each "{N}" replaced by the chosen item of list setting N.
    fn format_action_text(&self, text: &str) -> String {
        let mut out = String::new();
        let mut rest = text;
        while let Some(start) = rest.find('{') {
            out.push_str(&rest[..start]);
            let after = &rest[start + 1..];
            let id = after
                .find('}')
                .and_then(|end| Some((end, after[..end].parse::<u16>().ok()?)));
            match id {
                Some((end, id)) => {
                    out.push_str(&self.setting_value_label(id));
                    rest = &after[end + 1..];
                }
                None => {
                    out.push('{');
                    rest = after;
                }
            }
        }
        out.push_str(rest);
        out
    }

    /// The chosen item of list setting `id` (its value, for other settings).
    fn setting_value_label(&self, id: u16) -> String {
        let value = self
            .core_settings
            .as_ref()
            .and_then(|s| s.settings.iter().find(|s| s.0 == id))
            .map(|(_, value)| *value)
            .unwrap_or_default();
        let setting = self
            .core_info
            .as_ref()
            .and_then(|core| core.settings.iter().find(|s| s.id == id));
        match setting.map(|s| &s.inner) {
            Some(super::CoreSettingType::List { items }) => items
                .iter()
                .find(|item| item.value == value)
                .map(|item| item.label.to_string())
                .unwrap_or_else(|| value.to_string()),
            _ => value.to_string(),
        }
    }

    /// Send an updated setting to the FPGA.
    pub fn setting_send(&self, setting: &CoreSetting, value: u32) {
        let mut device = Device::lock();
        let base = if setting.mask != 0 {
            device.fpga.read_u32(setting.address).unwrap() & setting.mask
        } else {
            0
        };
        let _ = device.fpga.write_u32(setting.address, base | value);
    }
}
