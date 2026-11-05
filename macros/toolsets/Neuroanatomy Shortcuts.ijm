// IJ1 macro that installs a macro tool for toggling SNT's Shortcut Window

macro "AutoRunAndHide" {  // runs once file is selected from the 'More Tools' >> dropdown menu
    call("sc.fiji.snt.gui.cmds.ShortcutWindowCmd.showIfWasVisible");
}

macro "Neuroanatomy Shortcuts (alt+click for options) Action Tool - C037 T0b12S T6b12N Tdb12T" {
    if (isKeyDown("alt") || isKeyDown("shift") || isKeyDown("control")) {
        call("sc.fiji.snt.gui.cmds.ShortcutWindowCmd.showOptionsPrompt");
        setKeyDown("none");
        return;
    }
    call("sc.fiji.snt.gui.cmds.ShortcutWindowCmd.toggleVisibility"); // also syncs the icon
}
