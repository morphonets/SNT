#@ String(value="Have a break!") message
#@ double(min=0) minutes

"""
file:       Set_A_Timer.groovy
info:       Countdown timer with notification using SNT
"""

import sc.fiji.snt.gui.GuiUtils;
new GuiUtils().showNotification("${message}\nTime (${minutes}m) is up!", minutes*60*1000) // ms
