// SPDX-License-Identifier: MIT OR Apache-2.0

//! File bytes sent to a command as the request body.
//!
//! Desktop webviews send them raw. Android's webview bridge carries JSON only, so there the page
//! sends `{ "base64": "..." }` instead (see `bytesBody` in `ui/src/lib/ipc.ts`).

use base64::Engine as _;
use tauri::ipc::InvokeBody;

/// The bytes in `body`, raw or base64 in JSON, if it holds at most `max` of them.
pub(crate) fn bytes(body: &InvokeBody, max: usize) -> Option<Vec<u8>> {
    match body {
        InvokeBody::Raw(bytes) => (bytes.len() <= max).then(|| bytes.clone()),
        InvokeBody::Json(value) => {
            let encoded = value.get("base64")?.as_str()?;
            // Four base64 characters per three bytes: refuse oversized input before decoding.
            if encoded.len() > max.saturating_add(2) / 3 * 4 + 4 {
                return None;
            }
            let decoded = base64::engine::general_purpose::STANDARD
                .decode(encoded)
                .ok()?;
            (decoded.len() <= max).then_some(decoded)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_raw_and_base64_bodies() {
        assert_eq!(bytes(&InvokeBody::Raw(vec![1, 2, 3]), 10), Some(vec![1, 2, 3]));
        let json = InvokeBody::Json(serde_json::json!({ "base64": "AQID" }));
        assert_eq!(bytes(&json, 10), Some(vec![1, 2, 3]));
        assert_eq!(bytes(&json, 2), None);
        assert_eq!(bytes(&InvokeBody::Json(serde_json::json!({})), 10), None);
    }
}
