//! Status codes and the thread-local last-error slot.
//!
//! A `Result` cannot cross an `extern "C"` boundary, so every entry point returns an `i32` status
//! and stashes the human-readable detail here for `quill_last_error` to retrieve. The slot is
//! thread-local because the JVM calls the engine from whichever coroutine dispatcher thread is free,
//! and a shared slot would let one thread's failure surface as another's.

use std::cell::RefCell;

/// Status codes returned by every fallible entry point. `0` is success.
pub mod status {
    pub const OK: i32 = 0;
    pub const NULL_POINTER: i32 = -1;
    pub const INVALID_UTF8: i32 = -2;
    pub const OUT_OF_RANGE: i32 = -3;
    pub const INVALID_ARGUMENT: i32 = -4;
    pub const PANIC: i32 = -5;
}

thread_local! {
    static LAST_ERROR: RefCell<Option<String>> = const { RefCell::new(None) };
}

/// Records the detail for the most recent failure on this thread.
pub fn set_last_error(message: impl Into<String>) {
    let message = message.into();
    LAST_ERROR.with(|slot| {
        *slot.borrow_mut() = Some(message);
    });
}

/// Takes and clears the last error recorded on this thread.
pub fn take_last_error() -> Option<String> {
    LAST_ERROR.with(|slot| slot.borrow_mut().take())
}

/// Extracts a readable message from a panic payload.
pub fn describe_panic(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(message) = payload.downcast_ref::<&'static str>() {
        (*message).to_owned()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "panic with a non-string payload".to_owned()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stores_and_takes_an_error() {
        set_last_error("boom");
        assert_eq!(take_last_error().as_deref(), Some("boom"));
        assert_eq!(take_last_error(), None, "taking must clear the slot");
    }

    #[test]
    fn errors_do_not_leak_between_threads() {
        set_last_error("main thread");
        let observed = std::thread::spawn(take_last_error).join().unwrap();
        assert_eq!(observed, None);
        assert_eq!(take_last_error().as_deref(), Some("main thread"));
    }

    #[test]
    fn describes_both_panic_payload_shapes() {
        let static_payload = std::panic::catch_unwind(|| panic!("static message")).unwrap_err();
        assert_eq!(describe_panic(&*static_payload), "static message");

        let owned_payload = std::panic::catch_unwind(|| panic!("{}", "owned".to_owned())).unwrap_err();
        assert_eq!(describe_panic(&*owned_payload), "owned");
    }
}
