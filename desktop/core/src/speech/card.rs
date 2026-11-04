//! Which graphics card whisper.cpp listens on. Its Vulkan build takes the first GPU Vulkan lists, integrated or not,
//! and on a laptop with both that is often the processor's own graphics (an AMD Radeon or Intel Iris beside an RX
//! 6500M or an RTX), several times slower than the card beside it. So Chaya reads Vulkan's list itself, and when it
//! has a card of its own (discrete) as well as integrated graphics, hands whisper.cpp that card alone: ggml-vulkan's
//! `GGML_VK_VISIBLE_DEVICES`, an index into the same list.

/// What a device Vulkan lists is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    /// A graphics card of its own, with its own memory.
    Discrete,
    /// The processor's own graphics, sharing the PC's memory.
    Integrated,
    /// Anything else: a virtual GPU, or a driver that runs on the processor (Lavapipe).
    Other,
}

/// The device to hand whisper.cpp, among `kinds` in Vulkan's order: the first discrete card when there are integrated
/// graphics too; `None` to leave whisper.cpp's own choice, which is right when there is nothing to choose between.
pub fn pick(kinds: &[Kind]) -> Option<usize> {
    let first = kinds.iter().position(|k| *k == Kind::Discrete)?;
    kinds.contains(&Kind::Integrated).then_some(first)
}

/// The environment variable that tells ggml-vulkan which of Vulkan's devices it may use.
pub const VISIBLE: &str = "GGML_VK_VISIBLE_DEVICES";

/// The device whisper.cpp should listen on (`pick`), from Vulkan's list on this PC, read once.
pub fn chosen() -> Option<usize> {
    static CHOSEN: std::sync::OnceLock<Option<usize>> = std::sync::OnceLock::new();
    *CHOSEN.get_or_init(|| pick(&kinds()))
}

/// Vulkan's devices, in its order; none where Vulkan can't be loaded or lists nothing.
#[cfg(windows)]
fn kinds() -> Vec<Kind> {
    use ash::vk;
    // SAFETY: the loader is Windows' own vulkan-1.dll; the instance is made, read and destroyed here, and `entry`
    // outlives it.
    unsafe {
        let Ok(entry) = ash::Entry::load() else { return Vec::new() };
        let app = vk::ApplicationInfo::default().api_version(vk::API_VERSION_1_0);
        let Ok(instance) = entry.create_instance(&vk::InstanceCreateInfo::default().application_info(&app), None)
        else {
            return Vec::new();
        };
        let kinds = instance
            .enumerate_physical_devices()
            .unwrap_or_default()
            .into_iter()
            .map(|device| match instance.get_physical_device_properties(device).device_type {
                vk::PhysicalDeviceType::DISCRETE_GPU => Kind::Discrete,
                vk::PhysicalDeviceType::INTEGRATED_GPU => Kind::Integrated,
                _ => Kind::Other,
            })
            .collect();
        instance.destroy_instance(None);
        kinds
    }
}

#[cfg(not(windows))]
fn kinds() -> Vec<Kind> {
    Vec::new()
}

#[cfg(test)]
mod tests {
    use super::*;
    use Kind::*;

    #[test]
    fn a_laptop_with_both_listens_on_its_own_card() {
        // As Task Manager showed one: GPU 0 the processor's Radeon, GPU 1 an RX 6500M.
        assert_eq!(pick(&[Integrated, Discrete]), Some(1));
        assert_eq!(pick(&[Discrete, Integrated]), Some(0), "already first: named all the same");
        assert_eq!(pick(&[Other, Integrated, Discrete, Discrete]), Some(2), "the first card of its own");
    }

    #[test]
    fn with_nothing_to_choose_between_whisper_cpp_chooses() {
        assert_eq!(pick(&[Discrete]), None, "a desktop with one card");
        assert_eq!(pick(&[Integrated]), None, "a laptop with only its own graphics");
        assert_eq!(pick(&[Other]), None, "Lavapipe on CI");
        assert_eq!(pick(&[]), None, "no Vulkan");
    }
}
