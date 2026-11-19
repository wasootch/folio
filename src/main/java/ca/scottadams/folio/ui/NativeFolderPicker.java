package ca.scottadams.folio.ui;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.awt.Window;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Shows the native Windows (Explorer-style) "pick a folder" dialog via the
 * {@code IFileOpenDialog} COM interface.
 *
 * <p>Unlike {@code JFileChooser}, this dialog has Explorer's address bar (so
 * a path such as {@code \\SERVER_NAME\Books} can be pasted), and it browses
 * network locations asynchronously instead of freezing the UI.
 */
final class NativeFolderPicker {

    private static final Guid.CLSID CLSID_FILE_OPEN_DIALOG =
            new Guid.CLSID("{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}");
    private static final Guid.IID IID_FILE_OPEN_DIALOG =
            new Guid.IID("{D57C7288-D4AD-4768-BE02-9D969532D960}");
    private static final Guid.IID IID_SHELL_ITEM =
            new Guid.IID("{43826D1E-E718-42EE-BC55-A1E261C37BFE}");

    private static final int CLSCTX_INPROC_SERVER = 0x1;
    private static final int COINIT_APARTMENTTHREADED = 0x2;
    private static final int FOS_PATHMUSTEXIST = 0x800;
    private static final int FOS_FORCEFILESYSTEM = 0x40;
    private static final int FOS_PICKFOLDERS = 0x20;
    private static final int SIGDN_FILESYSPATH = 0x80058000;

    private static final int HRESULT_CANCELLED = 0x800704C7;
    private static final int RPC_E_CHANGED_MODE = 0x80010106;

    private NativeFolderPicker() {
    }

    /** True when the native dialog can be used on this platform. */
    static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    /**
     * Shows the dialog.
     *
     * @param initial folder to start in, or {@code null}
     * @return the chosen folder, or empty if the user cancelled
     * @throws IllegalStateException if the dialog could not be created or shown
     */
    static Optional<Path> pick(Window owner, String title, Path initial) {
        int initHr = Ole32.INSTANCE.CoInitializeEx(null, COINIT_APARTMENTTHREADED).intValue();
        // S_OK / S_FALSE both need a matching CoUninitialize; a mode mismatch means
        // COM is already usable on this thread and must not be uninitialized by us.
        boolean uninit = initHr >= 0;
        if (initHr < 0 && initHr != RPC_E_CHANGED_MODE) {
            throw new IllegalStateException("CoInitializeEx failed: 0x" + Integer.toHexString(initHr));
        }
        try {
            return show(owner, title, initial);
        } finally {
            if (uninit) {
                Ole32.INSTANCE.CoUninitialize();
            }
        }
    }

    private static Optional<Path> show(Window owner, String title, Path initial) {
        PointerByReference ppv = new PointerByReference();
        check("CoCreateInstance", Ole32.INSTANCE.CoCreateInstance(
                CLSID_FILE_OPEN_DIALOG, null, CLSCTX_INPROC_SERVER, IID_FILE_OPEN_DIALOG, ppv));
        FileOpenDialog dialog = new FileOpenDialog(ppv.getValue());
        try {
            IntByReference options = new IntByReference();
            check("GetOptions", dialog.getOptions(options));
            check("SetOptions", dialog.setOptions(
                    options.getValue() | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST));
            if (title != null) {
                dialog.setTitle(new WString(title));
            }
            if (initial != null) {
                setInitialFolder(dialog, initial);
            }

            HWND hwnd = owner == null ? null : new HWND(Native.getWindowPointer(owner));
            int hr = dialog.show(hwnd);
            if (hr == HRESULT_CANCELLED) {
                return Optional.empty();
            }
            check("Show", hr);

            PointerByReference resultRef = new PointerByReference();
            check("GetResult", dialog.getResult(resultRef));
            ShellItem item = new ShellItem(resultRef.getValue());
            try {
                PointerByReference nameRef = new PointerByReference();
                check("GetDisplayName", item.getDisplayName(SIGDN_FILESYSPATH, nameRef));
                Pointer namePtr = nameRef.getValue();
                try {
                    return Optional.of(Path.of(namePtr.getWideString(0)));
                } finally {
                    Ole32.INSTANCE.CoTaskMemFree(namePtr);
                }
            } finally {
                item.Release();
            }
        } finally {
            dialog.Release();
        }
    }

    private static void setInitialFolder(FileOpenDialog dialog, Path initial) {
        PointerByReference itemRef = new PointerByReference();
        int hr = Shell32Ext.INSTANCE.SHCreateItemFromParsingName(
                new WString(initial.toString()), null, IID_SHELL_ITEM, itemRef);
        if (hr < 0) {
            return; // best effort: the dialog just opens in its default location
        }
        ShellItem item = new ShellItem(itemRef.getValue());
        try {
            dialog.setFolder(item.getPointer());
        } finally {
            item.Release();
        }
    }

    private static void check(String call, HRESULT hr) {
        check(call, hr.intValue());
    }

    private static void check(String call, int hr) {
        if (hr < 0) {
            throw new IllegalStateException(call + " failed: HRESULT 0x" + Integer.toHexString(hr));
        }
    }

    /** Minimal vtable binding for {@code IFileOpenDialog} (only what we call). */
    private static final class FileOpenDialog extends Unknown {
        FileOpenDialog(Pointer p) {
            super(p);
        }

        int show(HWND owner) {            // IModalWindow::Show
            return _invokeNativeInt(3, new Object[]{getPointer(), owner});
        }

        int setOptions(int options) {     // IFileDialog::SetOptions
            return _invokeNativeInt(9, new Object[]{getPointer(), options});
        }

        int getOptions(IntByReference options) {
            return _invokeNativeInt(10, new Object[]{getPointer(), options});
        }

        int setFolder(Pointer shellItem) {
            return _invokeNativeInt(12, new Object[]{getPointer(), shellItem});
        }

        int setTitle(WString title) {
            return _invokeNativeInt(17, new Object[]{getPointer(), title});
        }

        int getResult(PointerByReference item) {
            return _invokeNativeInt(20, new Object[]{getPointer(), item});
        }
    }

    /** Minimal vtable binding for {@code IShellItem}. */
    private static final class ShellItem extends Unknown {
        ShellItem(Pointer p) {
            super(p);
        }

        int getDisplayName(int sigdn, PointerByReference name) {
            return _invokeNativeInt(5, new Object[]{getPointer(), sigdn, name});
        }
    }

    private interface Shell32Ext extends StdCallLibrary {
        Shell32Ext INSTANCE = Native.load("shell32", Shell32Ext.class, W32APIOptions.UNICODE_OPTIONS);

        int SHCreateItemFromParsingName(WString path, Pointer bindContext,
                                        Guid.GUID riid, PointerByReference item);
    }
}
