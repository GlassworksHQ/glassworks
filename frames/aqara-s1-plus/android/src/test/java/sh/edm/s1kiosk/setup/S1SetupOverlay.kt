package sh.edm.s1kiosk.setup

import android.content.Context
import sh.edm.s1kiosk.frame.AqaraS1Plus

/** The setup screen as the S1 app shows it: with [panes] on the Panes step and the S1's name on the HA step. */
internal fun s1SetupOverlay(context: Context, panes: List<SetupPane> = emptyList()) =
    SetupOverlay(context, panes, AqaraS1Plus.DISPLAY_NAME)
