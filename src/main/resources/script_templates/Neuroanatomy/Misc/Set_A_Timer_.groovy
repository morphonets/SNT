#@ String(value="Have a much deserved and needed break...") message
#@ double(min=0, stepSize=0.1) minutes

"""
file:       Set_A_Timer.groovy
info:       Countdown timer with notification using SNT
"""

import sc.fiji.snt.gui.GuiUtils;
new GuiUtils().showNotification("<HTML><b>${message}</b><br>Time (${minutes}m) is up!", (int) (minutes*60*1000)) // ms
