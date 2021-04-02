// IJ1 macro to launch SNT's Shortcut Window. To have it installed at startup:
// 1. Run Plugins>Macros>Startup Macros...
// 2. Uncomment and paste the following line to your StartupMacros.fiji.ijm file.
//    Alternatively, paste it to a .ijm file and save it in /Fiji.app/macros/AutoRun/
//
//    run("Install...", "install=["+ getDirectory("macros") +"toolsets"+File.separator+"Neuroanatomy Shortcuts.ijm]");

macro "AutoRunAndHide" {  // runs once file is selected from the 'More Tools' >> dropdown menu
    run("Neuroanatomy Shortcut Window");
}

macro "Neuroanatomy Shortcuts Action Tool - C037 T0b11S T6b11N Tdb11T" {
    if (isKeyDown("shift") || isKeyDown("alt")) {
        // forget previous location of window
        call("sc.fiji.snt.gui.cmds.ShortcutWindowCmd.resetFrameLocation");
    }
    call("sc.fiji.snt.gui.cmds.ShortcutWindowCmd.toggleVisibility");
    if (iconChangeSupported()) {
        if (call("sc.fiji.snt.gui.cmds.ShortcutWindowCmd.isVisible") == "true")
            call("ij.gui.Toolbar.setIcon", "Neuroanatomy Shortcuts Action Tool", "Ca30T0b11ST6b11NTdb11T");
        else
            call("ij.gui.Toolbar.setIcon", "Neuroanatomy Shortcuts Action Tool", "C037T0b11ST6b11NTdb11T");
    }
}

function iconChangeSupported() { // TODO: None of this is needed once Fiji ships IJ1.53h or newer
    fullVersion = getIJ1version();
    mainVersion = substring(fullVersion, 0, lengthOf(fullVersion)-1);
    subVersion = substring(fullVersion, lengthOf(fullVersion)-1);
    mainCheck = parseFloat(mainVersion) >= 1.53;
    subCheck = indexOf("abcdefg", subVersion) == -1;
    return (mainCheck && subCheck);
}

function getIJ1version() {
    version = getVersion();
    idx = indexOf(version, "/");
    if (idx > -1)
        return substring(version, idx+1);
    else
        return version;
}