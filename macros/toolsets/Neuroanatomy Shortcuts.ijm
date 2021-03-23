//IJ1 macro to launch the SNT's Shortcut command
macro "Neuroanatomy Shortcuts Action Tool - C037 T0b11S T6b11N Tdb11T" {
    if (isKeyDown("shift") || isKeyDown("alt")) {
        // forget previous location of window
        call("sc.fiji.snt.gui.cmds.ShortcutWindowCmd.resetFrameLocation");
    }
    run("Neuroanatomy Shortcut Window");
}
