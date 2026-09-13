use std::process::Command;

fn main() {
    embuild::espidf::sysenv::output();

    // The default "fluent" style's ListView animations are miscompiled by the
    // Xtensa LLVM backend (esp 1.93: the ROM list hangs the device) or fail to
    // compile (esp >= 1.94: "Cannot select ... PCREL_WRAPPER TargetConstantPool
    // [2 x float]"). "cosmic" avoids the construct; SLINT_STYLE overrides it.
    let style = std::env::var("SLINT_STYLE").unwrap_or_else(|_| "cosmic".to_string());
    println!("cargo:rerun-if-env-changed=SLINT_STYLE");
    slint_build::compile_with_config(
        "res/ui/main.slint",
        slint_build::CompilerConfiguration::new()
            .with_style(style)
            .embed_resources(slint_build::EmbedResourcesKind::EmbedForSoftwareRenderer),
    )
    .unwrap();

    // Get git commit hash
    println!("cargo:rerun-if-changed=../../.git/HEAD");
    let output = Command::new("git")
        .args(&["rev-parse", "HEAD"])
        .output()
        .expect("git command failed");
    let commit_hash = str::from_utf8(&output.stdout)
        .expect("git output invalid utf-8")
        .trim();
    println!("cargo:rustc-env=GIT_COMMIT={}", commit_hash);

    println!("cargo:rustc-link-arg=-Wl,--wrap=esp_panic_handler");
}
