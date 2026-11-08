// SPDX-License-Identifier: MIT OR Apache-2.0

//! Plays a stream with libVLC on Android: in the app's own player screen (`PlayerActivity`), or
//! in another player app (VLC, MX Player, ...).
//!
//! The other app reads the same loopback stream URL the built-in player uses, so playback starts
//! while the torrent is still downloading, and the other app's decoders cover formats and audio
//! the WebView cannot play.

use crate::error::IpcError;

/// Checks that `url` is one of this app's own stream URLs: plain http on the loopback address,
/// under `/s/`. Nothing else may be handed to another app.
fn stream_url(url: &str) -> Option<String> {
    let parsed = url::Url::parse(url.trim()).ok()?;
    let loopback = matches!(
        parsed.host(),
        Some(url::Host::Ipv4(ip)) if ip.is_loopback()
    );
    (parsed.scheme() == "http"
        && loopback
        && parsed.port().is_some()
        && parsed.path().starts_with("/s/")
        && parsed.username().is_empty())
    .then(|| parsed.into())
}

/// Opens the stream at `url` in another app, letting Android ask which player to use.
#[tauri::command]
#[allow(
    clippy::needless_pass_by_value,
    reason = "Tauri passes arguments by value"
)]
pub(crate) fn open_in_player(url: String) -> Result<(), IpcError> {
    let url = stream_url(&url).ok_or_else(|| IpcError::invalid("Not a stream of this app."))?;
    view(&url)
}

/// Plays the stream at `url` in the app's built-in libVLC player screen.
#[tauri::command]
#[allow(
    clippy::needless_pass_by_value,
    reason = "Tauri passes arguments by value"
)]
pub(crate) fn play_in_app(url: String, title: String) -> Result<(), IpcError> {
    let url = stream_url(&url).ok_or_else(|| IpcError::invalid("Not a stream of this app."))?;
    let title: String = title.chars().take(300).collect();
    play(&url, &title)
}

#[cfg(target_os = "android")]
fn play(url: &str, title: &str) -> Result<(), IpcError> {
    android::launch(url, Some(title)).map_err(|error| {
        tracing::warn!(%error, "could not open the built-in player");
        IpcError::invalid("The built-in player could not open.")
    })
}

#[cfg(not(target_os = "android"))]
fn play(_url: &str, _title: &str) -> Result<(), IpcError> {
    Err(IpcError::invalid(
        "The built-in VLC player is available on Android only.",
    ))
}

#[cfg(target_os = "android")]
fn view(url: &str) -> Result<(), IpcError> {
    android::launch(url, None).map_err(|error| {
        tracing::warn!(%error, "could not open another player");
        IpcError::invalid(
            "No player app could open this video. Install VLC from the Play Store and try again.",
        )
    })
}

#[cfg(not(target_os = "android"))]
fn view(_url: &str) -> Result<(), IpcError> {
    Err(IpcError::invalid(
        "Opening another player is available on Android only.",
    ))
}

/// The JNI calls: `startActivity(Intent(ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*"))`
/// for another app, or an explicit intent for `PlayerActivity` with the URL and title as extras.
#[cfg(target_os = "android")]
#[allow(unsafe_code)]
mod android {
    use jni::objects::{JObject, JValue};
    use jni::{jni_sig, jni_str};

    /// `Intent.FLAG_ACTIVITY_NEW_TASK`.
    const FLAG_ACTIVITY_NEW_TASK: i32 = 0x1000_0000;

    /// The app's package and its player screen (see `android/kotlin/PlayerActivity.kt`).
    const PACKAGE: &str = "com.tara44x.torrentxplayer";
    const PLAYER_ACTIVITY: &str = "com.tara44x.torrentxplayer.PlayerActivity";

    /// `title`: `Some` opens the built-in player, `None` another app.
    pub(super) fn launch(url: &str, title: Option<&str>) -> Result<(), jni::errors::Error> {
        let context = ndk_context::android_context();
        // SAFETY: tao records the process's Java VM in ndk-context before the app starts; it
        // stays valid for the life of the process.
        let vm = unsafe { jni::JavaVM::from_raw(context.vm().cast::<jni::sys::JavaVM>()) };
        vm.attach_current_thread(|env| -> Result<(), jni::errors::Error> {
            let started = start(env, context.context(), url, title);
            if env.exception_check() {
                env.exception_clear();
            }
            started
        })
    }

    fn start(
        env: &mut jni::Env<'_>,
        activity: *mut std::ffi::c_void,
        url: &str,
        title: Option<&str>,
    ) -> Result<(), jni::errors::Error> {
        // SAFETY: ndk-context holds a global reference to the activity, usable on any thread.
        let activity = unsafe { JObject::from_raw(env, activity.cast()) };
        if let Some(title) = title {
            let intent = env.new_object(jni_str!("android/content/Intent"), jni_sig!("()V"), &[])?;
            let package = env.new_string(PACKAGE)?;
            let class = env.new_string(PLAYER_ACTIVITY)?;
            env.call_method(
                &intent,
                jni_str!("setClassName"),
                jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;"),
                &[(&package).into(), (&class).into()],
            )?;
            for (key, value) in [("url", url), ("title", title)] {
                let key = env.new_string(key)?;
                let value = env.new_string(value)?;
                env.call_method(
                    &intent,
                    jni_str!("putExtra"),
                    jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;"),
                    &[(&key).into(), (&value).into()],
                )?;
            }
            env.call_method(
                &activity,
                jni_str!("startActivity"),
                jni_sig!("(Landroid/content/Intent;)V"),
                &[(&intent).into()],
            )?;
            return Ok(());
        }
        let text = env.new_string(url)?;
        let uri = env
            .call_static_method(
                jni_str!("android/net/Uri"),
                jni_str!("parse"),
                jni_sig!("(Ljava/lang/String;)Landroid/net/Uri;"),
                &[(&text).into()],
            )?
            .l()?;
        let action = env.new_string("android.intent.action.VIEW")?;
        let intent = env.new_object(
            jni_str!("android/content/Intent"),
            jni_sig!("(Ljava/lang/String;)V"),
            &[(&action).into()],
        )?;
        let mime = env.new_string("video/*")?;
        env.call_method(
            &intent,
            jni_str!("setDataAndType"),
            jni_sig!("(Landroid/net/Uri;Ljava/lang/String;)Landroid/content/Intent;"),
            &[(&uri).into(), (&mime).into()],
        )?;
        env.call_method(
            &intent,
            jni_str!("addFlags"),
            jni_sig!("(I)Landroid/content/Intent;"),
            &[JValue::Int(FLAG_ACTIVITY_NEW_TASK)],
        )?;
        env.call_method(
            &activity,
            jni_str!("startActivity"),
            jni_sig!("(Landroid/content/Intent;)V"),
            &[(&intent).into()],
        )?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hands_over_only_own_stream_urls() {
        assert!(stream_url("http://127.0.0.1:41234/s/7?t=abc").is_some());
        for bad in [
            "https://127.0.0.1:41234/s/7?t=abc",
            "http://example.org:80/s/7",
            "http://127.0.0.1/s/7",
            "http://127.0.0.1:41234/other",
            "file:///sdcard/x.mp4",
            "http://user@127.0.0.1:41234/s/7",
        ] {
            assert_eq!(stream_url(bad), None, "{bad}");
        }
    }
}
