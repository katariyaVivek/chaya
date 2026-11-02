//! A video's sound as the clip timeline draws it: how loud it is, `PER_SECOND` times a second, each the loudest moment
//! of its part as a byte (0 silent, 255 as loud as can be). ffmpeg decodes the sound to a stream of WAV, read as it
//! comes, so nothing is written but the peaks, which `video::Videos` keeps beside the video's file ("videos/12.peaks")
//! so they are read once.

use std::path::Path;
use std::process::Stdio;

use tokio::io::{AsyncRead, AsyncReadExt};
use tokio::process::Command;

/// Peaks a second: a tenth of a second is five of them, as fine as the timeline ever shows sound.
pub const PER_SECOND: u32 = 50;
/// The rate the sound is decoded at, enough to see how loud it is.
const RATE: u32 = 8_000;
/// The most bytes of WAV header read before its sound, past which it is not WAV as ffmpeg writes it.
const LONGEST_HEADER: usize = 64 * 1024;

#[derive(Debug, PartialEq, Eq)]
pub enum SoundError {
    /// ffmpeg is not in the engine, or would not start.
    NoProgram,
    /// The video has no sound ffmpeg could read.
    NoSound,
}

/// The peaks of `video`'s sound, decoded by `ffmpeg`.
pub async fn peaks(ffmpeg: &Path, video: &Path) -> Result<Vec<u8>, SoundError> {
    let mut child = Command::new(ffmpeg)
        .args(["-hide_banner", "-loglevel", "error", "-nostdin", "-i"])
        .arg(video)
        .args(["-vn", "-ac", "1", "-ar", &RATE.to_string(), "-c:a", "pcm_s16le", "-f", "wav", "pipe:1"])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .map_err(|_| SoundError::NoProgram)?;
    let stdout = child.stdout.take().ok_or(SoundError::NoProgram)?;
    let peaks = read_wav(stdout).await;
    let ended = child.wait().await.map(|s| s.success()).unwrap_or(false);
    match peaks {
        Some(peaks) if ended && !peaks.is_empty() => Ok(peaks),
        _ => Err(SoundError::NoSound),
    }
}

/// The peaks of 16-bit mono WAV at `RATE` read from `wav` to its end; `None` when it is not that.
pub async fn read_wav(mut wav: impl AsyncRead + Unpin) -> Option<Vec<u8>> {
    // RIFF, WAVE, then chunks ("fmt ", perhaps "LIST", ...) until "data", whose length a stream does not know.
    let mut head = [0u8; 12];
    wav.read_exact(&mut head).await.ok()?;
    if &head[0..4] != b"RIFF" || &head[8..12] != b"WAVE" {
        return None;
    }
    let mut skipped = 0;
    loop {
        let mut chunk = [0u8; 8];
        wav.read_exact(&mut chunk).await.ok()?;
        let size = u32::from_le_bytes([chunk[4], chunk[5], chunk[6], chunk[7]]) as usize;
        if &chunk[0..4] == b"data" {
            break;
        }
        skipped += size + 8;
        if skipped > LONGEST_HEADER {
            return None;
        }
        let mut body = vec![0u8; size + size % 2];
        wav.read_exact(&mut body).await.ok()?;
        if &chunk[0..4] == b"fmt " {
            let format = |at: usize| u16::from_le_bytes([body[at], body[at + 1]]);
            let rate = u32::from_le_bytes([body[4], body[5], body[6], body[7]]);
            // PCM, one channel, 16 bits, at RATE: what ffmpeg was asked for.
            if body.len() < 16 || format(0) != 1 || format(2) != 1 || rate != RATE || format(14) != 16 {
                return None;
            }
        }
    }
    let per_peak = (RATE / PER_SECOND) as usize;
    let mut peaks = Vec::new();
    let (mut loudest, mut samples) = (0u16, 0usize);
    let mut buffer = vec![0u8; 64 * 1024];
    let mut odd: Option<u8> = None;
    loop {
        let read = wav.read(&mut buffer).await.ok()?;
        if read == 0 {
            break;
        }
        let mut bytes = &buffer[..read];
        let mut sample = |low: u8, high: u8| {
            loudest = loudest.max(i16::from_le_bytes([low, high]).unsigned_abs());
            samples += 1;
            if samples == per_peak {
                peaks.push(byte(loudest));
                (loudest, samples) = (0, 0);
            }
        };
        if let Some(low) = odd.take() {
            sample(low, bytes[0]);
            bytes = &bytes[1..];
        }
        let (pairs, rest) = bytes.as_chunks::<2>();
        for [low, high] in pairs {
            sample(*low, *high);
        }
        odd = rest.first().copied();
    }
    if samples > 0 {
        peaks.push(byte(loudest));
    }
    Some(peaks)
}

/// A sample's size as a byte: 0 to 255.
fn byte(loudest: u16) -> u8 {
    ((u32::from(loudest) * 255 + 16_384) / 32_768) as u8
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A WAV stream as ffmpeg writes one to a pipe: its sizes unknown, an INFO list before the sound.
    fn wav(samples: &[i16]) -> Vec<u8> {
        let mut out = b"RIFF\xff\xff\xff\xffWAVE".to_vec();
        out.extend(b"fmt \x10\x00\x00\x00");
        out.extend(1u16.to_le_bytes()); // PCM
        out.extend(1u16.to_le_bytes()); // one channel
        out.extend(RATE.to_le_bytes());
        out.extend((RATE * 2).to_le_bytes());
        out.extend(2u16.to_le_bytes());
        out.extend(16u16.to_le_bytes());
        out.extend(b"LIST\x05\x00\x00\x00INFOx\x00");
        out.extend(b"data\xff\xff\xff\xff");
        for s in samples {
            out.extend(s.to_le_bytes());
        }
        out
    }

    #[tokio::test]
    async fn a_peak_is_the_loudest_moment_of_its_fiftieth_of_a_second() {
        let per = (RATE / PER_SECOND) as usize;
        // Silence, a quiet part, as loud as can be (both ways), and half a part at the end.
        let mut samples = vec![0i16; per];
        samples.extend((0..per).map(|i| if i == 7 { -8_192 } else { 100 }));
        samples.extend((0..per).map(|i| if i == 3 { i16::MIN } else { 0 }));
        samples.extend(vec![16_384i16; per / 2]);
        let peaks = read_wav(&wav(&samples)[..]).await.unwrap();
        assert_eq!(peaks, [0, 64, 255, 128]);
    }

    #[tokio::test]
    async fn a_sample_split_between_two_reads_is_still_one_sample() {
        // A reader that hands over an odd number of bytes at a time.
        let bytes = wav(&vec![i16::MAX; (RATE / PER_SECOND) as usize]);
        let (mut write, read) = tokio::io::duplex(7);
        let feeding = tokio::spawn(async move {
            use tokio::io::AsyncWriteExt;
            for piece in bytes.chunks(7) {
                write.write_all(piece).await.unwrap();
            }
        });
        let peaks = read_wav(read).await.unwrap();
        feeding.await.unwrap();
        assert_eq!(peaks, [255]);
    }

    #[tokio::test]
    async fn what_is_not_the_wav_asked_for_is_not_read() {
        assert_eq!(read_wav(&b"not a wav at all"[..]).await, None);
        let mut stereo = wav(&[0; 10]);
        stereo[22] = 2; // two channels
        assert_eq!(read_wav(&stereo[..]).await, None);
    }

    /// With CI's own ffmpeg (`CHAYA_FFMPEG`), the sound of a real file.
    #[tokio::test]
    async fn the_sound_of_a_real_video() {
        let Some(ffmpeg) = std::env::var_os("CHAYA_FFMPEG").map(std::path::PathBuf::from) else {
            eprintln!("skipped: no CHAYA_FFMPEG");
            return;
        };
        let video = Path::new(env!("CARGO_MANIFEST_DIR")).join("../app/tour/fixtures/Ask not.mp4");
        let peaks = peaks(&ffmpeg, &video).await.unwrap();
        // Eleven seconds or so of a speech: 50 a second, and loud somewhere.
        assert!((400..700).contains(&peaks.len()), "{}", peaks.len());
        assert!(peaks.iter().any(|&p| p > 64));
        let silent = Path::new(env!("CARGO_MANIFEST_DIR")).join("Cargo.toml");
        assert_eq!(super::peaks(&ffmpeg, &silent).await, Err(SoundError::NoSound));
    }
}
