use std::{
    cell::UnsafeCell,
    ops::{Deref, DerefMut},
    sync::atomic::{AtomicBool, Ordering},
};

/// The data is word-aligned (first, in a word-aligned struct): the SD card
/// driver reads and writes sectors by DMA only to and from word-aligned
/// buffers, and otherwise one sector per command through a buffer of its own.
#[repr(C, align(4))]
pub struct ScratchBuffer<const N: usize> {
    data: UnsafeCell<[u8; N]>,
    used: AtomicBool,
}

impl<const N: usize> ScratchBuffer<N> {
    pub const fn new() -> Self {
        ScratchBuffer {
            data: UnsafeCell::new([0u8; N]),
            used: AtomicBool::new(false),
        }
    }

    pub fn take<'a>(&'a self) -> Option<ScratchBufferHandle<'a>> {
        let used = self.used.swap(true, Ordering::SeqCst);
        if !used {
            // SAFETY: we have locked the buffer with 'used'.
            let data = unsafe { &mut *self.data.get() };
            Some(ScratchBufferHandle {
                data: Some(data),
                used: &self.used,
            })
        } else {
            None
        }
    }
}

unsafe impl<const N: usize> Send for ScratchBuffer<N> {}
unsafe impl<const N: usize> Sync for ScratchBuffer<N> {}

pub struct ScratchBufferHandle<'a> {
    data: Option<&'a mut [u8]>,
    used: &'a AtomicBool,
}

impl Deref for ScratchBufferHandle<'_> {
    type Target = [u8];

    fn deref(&self) -> &Self::Target {
        self.data.as_ref().unwrap()
    }
}

impl DerefMut for ScratchBufferHandle<'_> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        self.data.as_mut().unwrap()
    }
}

impl<'a> Drop for ScratchBufferHandle<'a> {
    fn drop(&mut self) {
        self.data = None;
        self.used.store(false, Ordering::SeqCst);
    }
}
