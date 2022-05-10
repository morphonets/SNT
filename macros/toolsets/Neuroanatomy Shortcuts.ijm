// IJ1 macro that installs a macro tool for toggling SNT's Shortcut Window

macro "AutoRunAndHide" {  // runs once file is selected from the 'More Tools' >> dropdown menu
    run("Neuroanatomy Shortcut Window");
}

macro "Neuroanatomy Shortcuts Action Tool - C037 T0b11S T6b11N Tdb11T" {
    if (isKeyDown("shift") || isKeyDown("alt")) {
        // forget previous location of window
        call("sc.fiji.snt.gui.cmds.ShortcutWindowCmd.resetFrameLocation");
    }
    call("sc.fiji.snt.gui.cmds.ShortcutWindowCmd.toggleVisibility");
    if (call("sc.fiji.snt.gui.cmds.ShortcutWindowCmd.isVisible") == "true")
        call("ij.gui.Toolbar.setIcon", "Neuroanatomy Shortcuts Action Tool", "Ca30T0b11ST6b11NTdb11T");
    else
        call("ij.gui.Toolbar.setIcon", "Neuroanatomy Shortcuts Action Tool", "C037T0b11ST6b11NTdb11T");
}
