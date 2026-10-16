use std::{io, sync::Mutex};
use tauri::{WebviewWindow, WindowEvent};
use windows_sys::Win32::{
    Foundation::HWND,
    UI::{
        HiDpi::{GetDpiForWindow, GetSystemMetricsForDpi},
        WindowsAndMessaging::{
            CreateIconFromResourceEx, DestroyIcon, SendMessageW, HICON, ICON_BIG, ICON_SMALL,
            SM_CXSMICON, SM_CYSMICON, WM_SETICON,
        },
    },
};

const LARGE_ICON: &[u8] = include_bytes!("../icons/icon.png");
const SMALL_ICONS: &[(i32, &[u8])] = &[
    (16, include_bytes!("../icons/window/16x16.png")),
    (20, include_bytes!("../icons/window/20x20.png")),
    (24, include_bytes!("../icons/window/24x24.png")),
    (28, include_bytes!("../icons/window/28x28.png")),
    (32, include_bytes!("../icons/window/32x32.png")),
    (40, include_bytes!("../icons/window/40x40.png")),
    (48, include_bytes!("../icons/window/48x48.png")),
    (64, include_bytes!("../icons/window/64x64.png")),
];

// These are owned image handles, not windows. Integer storage allows the listener
// to be Send without claiming that an HWND can be used from any thread.
struct OwnedIcon(usize);

impl OwnedIcon {
    fn from_png(png: &[u8], width: i32, height: i32) -> io::Result<Self> {
        // Windows 10+ accepts a PNG as an icon resource and copies its pixels.
        let handle = unsafe {
            CreateIconFromResourceEx(
                png.as_ptr(),
                png.len() as u32,
                1,
                0x0003_0000,
                width,
                height,
                0,
            )
        };
        if handle.is_null() {
            Err(io::Error::last_os_error())
        } else {
            Ok(Self(handle as usize))
        }
    }

    fn handle(&self) -> HICON {
        self.0 as HICON
    }

    fn set(&self, window: HWND, kind: u32) {
        // Called synchronously on the window thread. WM_SETICON borrows the
        // handle; it does not transfer ownership of ours or the previous icon.
        unsafe { SendMessageW(window, WM_SETICON, kind as usize, self.handle() as isize) };
    }
}

impl Drop for OwnedIcon {
    fn drop(&mut self) {
        // Only handles created without LR_SHARED by from_png are owned here.
        unsafe { DestroyIcon(self.handle()) };
    }
}

struct WindowIcons {
    small: OwnedIcon,
    _large: OwnedIcon,
    small_size: (i32, i32),
}

impl WindowIcons {
    fn new(window: HWND, dpi: u32) -> io::Result<Self> {
        let small_size = small_size(dpi);
        let small = small_icon(small_size)?;
        let large = OwnedIcon::from_png(LARGE_ICON, 512, 512)?;
        small.set(window, ICON_SMALL);
        large.set(window, ICON_BIG);
        Ok(Self {
            small,
            _large: large,
            small_size,
        })
    }

    fn refresh(&mut self, window: HWND, dpi: u32) -> io::Result<()> {
        let size = small_size(dpi);
        if size != self.small_size {
            let small = small_icon(size)?;
            small.set(window, ICON_SMALL);
            // Replace only after Windows references the new handle.
            self.small = small;
            self.small_size = size;
        }
        Ok(())
    }
}

fn small_size(dpi: u32) -> (i32, i32) {
    let dpi = dpi.max(96);
    unsafe {
        (
            GetSystemMetricsForDpi(SM_CXSMICON, dpi).max(1),
            GetSystemMetricsForDpi(SM_CYSMICON, dpi).max(1),
        )
    }
}

fn small_icon(size: (i32, i32)) -> io::Result<OwnedIcon> {
    let png = SMALL_ICONS
        .iter()
        .find(|(pixels, _)| *pixels >= size.0.max(size.1))
        .unwrap_or_else(|| SMALL_ICONS.last().unwrap())
        .1;
    OwnedIcon::from_png(png, size.0, size.1)
}

pub fn install(window: &WebviewWindow) -> tauri::Result<()> {
    let hwnd = window.hwnd()?.0 as HWND;
    let icons = Mutex::new(Some(WindowIcons::new(hwnd, unsafe {
        GetDpiForWindow(hwnd)
    })?));
    let hwnd = hwnd as usize;

    // Tauri dispatches native window events on its event-loop thread. Capture
    // no WebviewWindow/AppHandle so the listener cannot retain its own window.
    window.on_window_event(move |event| match event {
        WindowEvent::ScaleFactorChanged { scale_factor, .. } => {
            if let Ok(mut icons) = icons.lock() {
                if let Some(icons) = icons.as_mut() {
                    let dpi = (scale_factor * 96.0).round() as u32;
                    if let Err(error) = icons.refresh(hwnd as HWND, dpi) {
                        eprintln!("Unable to update title bar icon: {error}");
                    }
                }
            }
        }
        WindowEvent::Destroyed => {
            if let Ok(mut icons) = icons.lock() {
                // The native window no longer references either handle.
                icons.take();
            }
        }
        _ => {}
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{mem, ptr};
    use windows_sys::Win32::{
        Graphics::Gdi::{DeleteObject, GetObjectW, BITMAP},
        UI::WindowsAndMessaging::{
            CreateWindowExW, DestroyWindow, GetIconInfo, ICONINFO, WM_GETICON,
        },
    };

    fn dimensions(icon: HICON) -> Option<(i32, i32)> {
        let mut info: ICONINFO = unsafe { mem::zeroed() };
        if unsafe { GetIconInfo(icon, &mut info) } == 0 {
            return None;
        }
        let mut bitmap: BITMAP = unsafe { mem::zeroed() };
        let result = unsafe {
            GetObjectW(
                info.hbmColor,
                mem::size_of::<BITMAP>() as i32,
                &mut bitmap as *mut _ as _,
            )
        };
        unsafe {
            DeleteObject(info.hbmColor);
            DeleteObject(info.hbmMask);
        }
        assert_ne!(result, 0);
        Some((bitmap.bmWidth, bitmap.bmHeight))
    }

    struct TestWindow(HWND);

    impl Drop for TestWindow {
        fn drop(&mut self) {
            unsafe { DestroyWindow(self.0) };
        }
    }

    #[test]
    fn native_icons_keep_separate_sizes_and_release_replaced_handles() {
        let class: Vec<u16> = "STATIC\0".encode_utf16().collect();
        let window = TestWindow(unsafe {
            CreateWindowExW(
                0,
                class.as_ptr(),
                ptr::null(),
                0,
                0,
                0,
                100,
                100,
                ptr::null_mut(),
                ptr::null_mut(),
                ptr::null_mut(),
                ptr::null(),
            )
        });
        assert!(!window.0.is_null());
        let mut icons = WindowIcons::new(window.0, 96).unwrap();
        let large = unsafe { SendMessageW(window.0, WM_GETICON, ICON_BIG as usize, 0) } as HICON;
        assert_eq!(dimensions(large), Some((512, 512)));

        for dpi in [96, 120, 144, 168, 192, 240, 288, 384, 144, 96] {
            let previous = icons.small.handle();
            let previous_size = icons.small_size;
            icons.refresh(window.0, dpi).unwrap();
            let small =
                unsafe { SendMessageW(window.0, WM_GETICON, ICON_SMALL as usize, 0) } as HICON;
            assert_eq!(dimensions(small), Some(small_size(dpi)));
            assert_eq!(
                unsafe { SendMessageW(window.0, WM_GETICON, ICON_BIG as usize, 0) } as HICON,
                large
            );
            if small_size(dpi) != previous_size {
                assert_ne!(small, previous);
                assert_eq!(dimensions(previous), None);
            } else {
                assert_eq!(small, previous);
            }
        }

        let small = icons.small.handle();
        drop(window);
        drop(icons);
        assert_eq!(dimensions(small), None);
        assert_eq!(dimensions(large), None);
    }
}
