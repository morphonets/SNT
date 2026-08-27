#@ String(value="Have a much deserved and needed break...") message
#@ double(min=0, stepSize=0.1, max=180) minutes

"""
file:       Set_A_Timer.groovy
info:       Countdown timer with notification using SNT
"""

import sc.fiji.snt.gui.GuiUtils;

GuiUtils.notify(
	"**${message}** Time (${minutes}m) is up!", // notification text (basic markdown syntax supported)
	(int) (minutes*60*1000)) // notification delay in miliseconds 
