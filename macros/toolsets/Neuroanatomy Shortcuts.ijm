// IJ1 macro that installs a macro tool for toggling SNT's Shortcut Window

macro "AutoRunAndHide" {  // runs once file is selected from the 'More Tools' >> dropdown menu
    call("sc.fiji.snt.gui.cmds.ShortcutWindowCmd.show");
}

macro "Neuroanatomy Shortcuts (alt+click for options) Action Tool - C037 T0b11S T6b11N Tdb11T" {
    if (isKeyDown("alt") || isKeyDown("shift") || isKeyDown("control")) {
        call("sc.fiji.snt.gui.cmds.ShortcutWindowCmd.showOptionsPrompt");
        setKeyDown("none");
        return;
    }
    call("sc.fiji.snt.gui.cmds.ShortcutWindowCmd.toggleVisibility");
    if (call("sc.fiji.snt.gui.cmds.ShortcutWindowCmd.isVisible") == "true")
        call("ij.gui.Toolbar.setIcon", "Neuroanatomy Shortcuts (alt+click for options) Action Tool", "Ca30T0b11ST6b11NTdb11T");
    else
        call("ij.gui.Toolbar.setIcon", "Neuroanatomy Shortcuts (alt+click for options) Action Tool", "C037T0b11ST6b11NTdb11T");
}
