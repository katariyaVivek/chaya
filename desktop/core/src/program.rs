//! Starting the programs Chaya carries (the engine's Python, ffmpeg, whisper). Every one is started from here, so none
//! opens a console window: Chaya is a window program, and on Windows a console program it starts gets a console of its
//! own unless told not to, which shows as a black window flashing up for a moment. `tests/programs.rs` checks that
//! nothing else starts one.

use std::ffi::OsStr;

/// Windows' `CREATE_NO_WINDOW`: the program runs with no console.
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// `program`, to be run with no console window (tokio's, for running while other work goes on).
pub fn program(program: impl AsRef<OsStr>) -> tokio::process::Command {
    #[allow(unused_mut)]
    let mut command = tokio::process::Command::new(program);
    #[cfg(windows)]
    command.creation_flags(CREATE_NO_WINDOW);
    command
}

/// `program`, to be run with no console window, waited on by the thread that starts it.
pub fn program_blocking(program: impl AsRef<OsStr>) -> std::process::Command {
    #[allow(unused_mut)]
    let mut command = std::process::Command::new(program);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    command
}
