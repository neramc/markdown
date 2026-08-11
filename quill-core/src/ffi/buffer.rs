//! The out-parameter buffer used to hand owned byte arrays to the JVM.

/// A heap buffer owned by Rust and borrowed by the caller until it is returned to `quill_buf_free`.
///
/// `cap` travels alongside `len` because Rust's allocator needs the original capacity to free the
/// block: reconstructing the `Vec` with only the length would be undefined behaviour whenever the
/// encoder over-allocated, which it usually does.
#[repr(C)]
#[derive(Debug)]
pub struct QuillBuf {
    pub ptr: *mut u8,
    pub len: usize,
    pub cap: usize,
}

impl QuillBuf {
    pub const fn empty() -> Self {
        Self {
            ptr: std::ptr::null_mut(),
            len: 0,
            cap: 0,
        }
    }

    /// Transfers ownership of `bytes` to the caller.
    pub fn from_vec(bytes: Vec<u8>) -> Self {
        let mut bytes = std::mem::ManuallyDrop::new(bytes);
        Self {
            ptr: bytes.as_mut_ptr(),
            len: bytes.len(),
            cap: bytes.capacity(),
        }
    }

    /// Reclaims the allocation, leaving the struct empty.
    ///
    /// # Safety
    ///
    /// The buffer must have come from [`QuillBuf::from_vec`] and must not have been freed before.
    pub unsafe fn release(&mut self) {
        if !self.ptr.is_null() {
            // SAFETY: `ptr`, `len` and `cap` were produced by `from_vec` from a single `Vec<u8>`
            // allocation and have not been mutated since, so reconstructing that Vec is valid.
            drop(unsafe { Vec::from_raw_parts(self.ptr, self.len, self.cap) });
        }
        *self = Self::empty();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_bytes() {
        let mut buffer = QuillBuf::from_vec(vec![1, 2, 3, 4]);
        assert!(!buffer.ptr.is_null());
        assert_eq!(buffer.len, 4);
        assert!(buffer.cap >= 4);

        // SAFETY: read back exactly the bytes we just handed over.
        let observed = unsafe { std::slice::from_raw_parts(buffer.ptr, buffer.len) };
        assert_eq!(observed, &[1, 2, 3, 4]);

        // SAFETY: produced by from_vec and not yet released.
        unsafe { buffer.release() };
        assert!(buffer.ptr.is_null());
        assert_eq!((buffer.len, buffer.cap), (0, 0));
    }

    #[test]
    fn releasing_twice_is_harmless() {
        let mut buffer = QuillBuf::from_vec(vec![9]);
        // SAFETY: the first release owns the allocation; the second sees a null pointer and no-ops,
        // which is what makes an accidental double free from the JVM side survivable.
        unsafe {
            buffer.release();
            buffer.release();
        }
        assert!(buffer.ptr.is_null());
    }

    #[test]
    fn handles_an_empty_vec() {
        let mut buffer = QuillBuf::from_vec(Vec::new());
        assert_eq!(buffer.len, 0);
        // SAFETY: from_vec output, released once.
        unsafe { buffer.release() };
        assert_eq!(buffer.cap, 0);
    }

    #[test]
    fn empty_is_all_zero() {
        let buffer = QuillBuf::empty();
        assert!(buffer.ptr.is_null());
        assert_eq!((buffer.len, buffer.cap), (0, 0));
    }
}
