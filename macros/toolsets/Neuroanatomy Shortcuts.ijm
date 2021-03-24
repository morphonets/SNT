//
// IJ1 macro to launch SNT's Shortcut Window. To have it installed at startup:
// 1. Run Plugins>Macros>Startup Macros...
// 2. Uncomment and paste the following line to your StartupMacros.fiji.ijm file.
//    Alternatively, paste it to a .ijm file and save it in /Fiji.app/macros/AutoRun/
//
//    run("Install...", "install=["+ getDirectory("macros") +"toolsets"+File.separator+"Neuroanatomy Shortcuts.ijm]");
//
macro "Neuroanatomy Shortcuts Action Tool - C037 T0b11S T6b11N Tdb11T" {
    if (isKeyDown("shift") || isKeyDown("alt")) {
        // forget previous location of window
        call("sc.fiji.snt.gui.cmds.ShortcutWindowCmd.resetFrameLocation");
    }
    run("Neuroanatomy Shortcut Window");
}

